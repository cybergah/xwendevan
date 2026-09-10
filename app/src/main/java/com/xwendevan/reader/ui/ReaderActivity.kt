package com.xwendevan.reader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.xwendevan.reader.R
import com.xwendevan.reader.Settings
import com.xwendevan.reader.data.Bookmark
import com.xwendevan.reader.data.Bookmarks
import com.xwendevan.reader.data.Library
import com.xwendevan.reader.databinding.ActivityReaderBinding
import com.xwendevan.reader.doc.DocumentLoader
import com.xwendevan.reader.doc.DocumentModel
import com.xwendevan.reader.doc.LangDetect
import com.xwendevan.reader.doc.Ocr
import com.xwendevan.reader.doc.ParsedParagraph
import com.xwendevan.reader.doc.PdfSource
import com.xwendevan.reader.doc.PdfTextExtractor
import com.xwendevan.reader.player.PlayerService
import com.xwendevan.reader.player.ReaderPlayer
import com.xwendevan.reader.tts.TtsEngine
import com.xwendevan.reader.tts.VoiceRepository

/** Belgeyi gösteren ve seslendiren ekran. PDF sayfaları olduğu gibi çizilir. */
class ReaderActivity : AppCompatActivity(), ReaderPlayer.Listener {

    private lateinit var b: ActivityReaderBinding
    private val ui = Handler(Looper.getMainLooper())

    private var documentUri: Uri? = null
    private var document: DocumentModel? = null
    private var pdfSource: PdfSource? = null
    private var pdfAdapter: PdfPageAdapter? = null
    private var textAdapter: ParagraphAdapter? = null
    private var parseThread: Thread? = null

    private var followReading = true
    private var seeking = false
    private var voiceLoading = false
    private var autoPlayWhenReady = false
    private var pendingStartIndex = -1

    /** Okuma başladığı an (elapsedRealtime); dinleme süresi sayacı. */
    private var playingSince = 0L

    /** Dil kestirimi bir kez yapılır. */
    private var languageChecked = false

    /** OCR sürerken ikinci kez başlatılmasın. */
    private var ocrRunning = false
    private var barsVisible = true

    // Üç dokunuş algılama
    private var tapCount = 0
    private var lastTapAt = 0L
    private var pendingSentence = -1
    private val tapRunnable = Runnable {
        if (tapCount == 1) toggleBars()
        tapCount = 0
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateStatus()
            ui.postDelayed(this, 500)
        }
    }

    // ------------------------------------------------------------------ yaşam döngüsü

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        wireControls()

        val uriString = intent.getStringExtra(EXTRA_URI)
            ?: intent.data?.toString()
            ?: (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.toString()
        if (uriString == null) {
            finish(); return
        }
        val uri = Uri.parse(uriString)
        documentUri = uri

        voiceLoading = true
        VoiceSheet.loadSaved(this) {
            voiceLoading = false
            refreshVoiceBar()
            document?.let {
                maybeSwitchVoiceForLanguage(it)
                maybeAutoPlay(it)
            }
        }

        openDocument(uri)
    }

    override fun onStart() {
        super.onStart()
        ReaderPlayer.addListener(this)
        ui.post(ticker)
        applyKeepScreenOn()
        updateControls()
    }

    override fun onStop() {
        super.onStop()
        ReaderPlayer.removeListener(this)
        ui.removeCallbacks(ticker)
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        parseThread?.interrupt()
        pdfAdapter?.shutdown()
        if (isFinishing && !ReaderPlayer.isPlaying) {
            ReaderPlayer.stop()
            PlayerService.stopService(this)
            runCatching { pdfSource?.close() }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_voices -> showVoices()
            R.id.action_search -> showSearch()
            R.id.action_bookmark_add -> addBookmark()
            R.id.action_bookmarks -> showBookmarks()
            R.id.action_sleep -> SleepDialog.show(this)
            R.id.action_export -> exportDocument()
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_font -> showFontDialog()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------ OCR

    /** Metin katmanı olmayan PDF: daha önce tanınmışsa kullan, değilse sor. */
    private fun maybeOfferOcr(model: DocumentModel) {
        val source = pdfSource ?: return
        val key = documentUri?.toString() ?: return
        if (ocrRunning || model.sentenceCount > 0) return

        Ocr.cached(this, key)?.let { paragraphs ->
            model.addParagraphs(paragraphs)
            textAdapter?.syncCount()
            ReaderPlayer.setDocument(model, 0)
            applyPendingStart(model)
            updateStatus()
            Toast.makeText(this, R.string.ocr_from_cache, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.ocr_title)
            .setMessage(getString(R.string.ocr_offer, source.pageCount))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.ocr_run) { _, _ -> runOcr(model, source, key) }
            .show()
    }

    private fun runOcr(model: DocumentModel, source: PdfSource, key: String) {
        ocrRunning = true
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val progress = AlertDialog.Builder(this)
            .setTitle(R.string.ocr_title)
            .setMessage(getString(R.string.ocr_progress, 0, source.pageCount))
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ -> cancelled.set(true) }
            .show()

        Thread({
            val result = runCatching {
                Ocr.run(
                    ctx = this,
                    key = key,
                    pageCount = source.pageCount,
                    render = { page -> source.renderOnce(page, Ocr.RENDER_WIDTH) },
                    cancelled = { cancelled.get() }
                ) { page, paragraphs ->
                    ui.post {
                        if (document === model && paragraphs.isNotEmpty()) {
                            model.addBatch(paragraphs.map { ParsedParagraph(it, page) })
                            textAdapter?.syncCount()
                            updateStatus()
                        }
                        progress.setMessage(getString(R.string.ocr_progress, page + 1, source.pageCount))
                    }
                }
            }
            ui.post {
                ocrRunning = false
                runCatching { progress.dismiss() }
                if (document !== model) return@post
                result.onSuccess { paragraphs ->
                    if (paragraphs.isEmpty()) {
                        Toast.makeText(this, R.string.ocr_none, Toast.LENGTH_LONG).show()
                    } else {
                        ReaderPlayer.setDocument(model, 0)
                        applyPendingStart(model)
                        maybeSwitchVoiceForLanguage(model)
                        Toast.makeText(
                            this,
                            getString(R.string.ocr_done, model.sentenceCount),
                            Toast.LENGTH_LONG
                        ).show()
                        maybeAutoPlay(model)
                    }
                    updateStatus()
                }.onFailure {
                    Toast.makeText(this, getString(R.string.ocr_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }, "xw-ocr").start()
    }

    // ------------------------------------------------------------------ dil algılama

    /**
     * Belgenin ilk paragraflarına bakıp dili kestirir; ayar açıksa ve o dile
     * uygun gömülü bir ses varsa sesi kendiliğinden değiştirir.
     */
    private fun maybeSwitchVoiceForLanguage(model: DocumentModel) {
        if (languageChecked || !Settings.autoVoice) return
        // Ses henüz yüklenmediyse karşılaştıracak bir dil yok; yüklenince yeniden bakılır
        val current = TtsEngine.voice ?: return

        val sample = StringBuilder()
        var i = 0
        while (i < model.paragraphCount && sample.length < 1200) {
            sample.append(model.paragraphAt(i)).append(' ')
            i++
        }
        if (sample.length < 200 && !model.isComplete) return
        languageChecked = true

        val lang = LangDetect.detect(sample.toString())
        if (lang.isBlank() || current.language.startsWith(lang)) return

        val target = VoiceRepository.listVoices(this).firstOrNull { it.language.startsWith(lang) } ?: return
        Toast.makeText(this, getString(R.string.auto_voice_switch, target.displayName), Toast.LENGTH_LONG).show()
        VoiceSheet.load(this, target) { refreshVoiceBar() }
    }

    // ------------------------------------------------------------------ yer imi · arama

    private fun addBookmark() {
        val uri = documentUri?.toString() ?: return
        val sentence = document?.sentenceAt(ReaderPlayer.currentIndex) ?: return
        val added = Bookmarks.add(
            this,
            Bookmark(uri, sentence.index, sentence.text.take(120), sentence.page, System.currentTimeMillis())
        )
        Toast.makeText(
            this,
            if (added) R.string.bookmark_added else R.string.bookmark_exists,
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showBookmarks() {
        val uri = documentUri?.toString() ?: return
        val marks = Bookmarks.forDocument(this, uri)
        if (marks.isEmpty()) {
            Toast.makeText(this, R.string.bookmarks_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = marks.map { m ->
            val page = if (m.page >= 0) getString(R.string.page_of, m.page + 1, pdfSource?.pageCount ?: (m.page + 1)) + " · " else ""
            "$page${m.text}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmarks)
            .setItems(labels) { _, which -> ReaderPlayer.seekTo(marks[which].sentence, keepPlaying = ReaderPlayer.isPlaying) }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.delete_all) { _, _ ->
                Bookmarks.removeDocument(this, uri)
                Toast.makeText(this, R.string.bookmarks_cleared, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSearch() {
        val doc = document ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setSingleLine()
            val pad = dp(20)
            setPadding(pad, dp(12), pad, dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_search)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.search_go) { _, _ ->
                runSearch(doc, input.text?.toString()?.trim().orEmpty())
            }
            .show()
    }

    private fun runSearch(doc: DocumentModel, query: String) {
        if (query.length < 2) return
        val hits = ArrayList<com.xwendevan.reader.doc.Sentence>()
        for (i in 0 until doc.sentenceCount) {
            val s = doc.sentenceAt(i) ?: continue
            if (s.text.contains(query, ignoreCase = true)) hits.add(s)
            if (hits.size >= 200) break
        }
        if (hits.isEmpty()) {
            Toast.makeText(this, getString(R.string.search_none, query), Toast.LENGTH_SHORT).show()
            return
        }
        val labels = hits.map { s ->
            val page = if (s.page >= 0) getString(R.string.page_short, s.page + 1) + " · " else ""
            "$page${s.text.take(120)}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.search_results, hits.size))
            .setItems(labels) { _, which -> ReaderPlayer.seekTo(hits[which].index, keepPlaying = ReaderPlayer.isPlaying) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ MP3

    private fun exportDocument() {
        val doc = document ?: return
        if (TtsEngine.voice == null) {
            Toast.makeText(this, R.string.no_voice, Toast.LENGTH_LONG).show()
            return
        }
        if (!doc.isComplete) {
            Toast.makeText(this, R.string.export_wait_parse, Toast.LENGTH_LONG).show()
            return
        }
        val options = arrayOf(
            getString(R.string.export_single),
            getString(R.string.export_parts, 15),
            getString(R.string.export_parts, 30)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_export_mp3)
            .setItems(options) { _, which ->
                DocumentExport.start(this, doc, when (which) {
                    1 -> 15
                    2 -> 30
                    else -> 0
                })
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ belge

    private fun openDocument(uri: Uri) {
        val title = DocumentLoader.displayName(this, uri)
        val kind = DocumentLoader.kindOf(this, uri)
        if (kind == DocumentLoader.Kind.LEGACY_DOC) {
            Toast.makeText(this, R.string.legacy_doc, Toast.LENGTH_LONG).show()
            finish(); return
        }
        supportActionBar?.title = title

        val entry = Library.touch(
            this, uri.toString(), title,
            when (kind) {
                DocumentLoader.Kind.PDF -> "pdf"
                DocumentLoader.Kind.DOCX -> "docx"
                DocumentLoader.Kind.EPUB -> "epub"
                else -> "txt"
            }
        )
        Library.markOpened(this, uri.toString())
        pendingStartIndex = entry.sentenceIndex
        autoPlayWhenReady = Settings.autoScroll || true   // açılışta okumaya başla

        val model = DocumentModel(
            title, uri.toString(),
            if (kind == DocumentLoader.Kind.PDF) DocumentModel.Kind.PDF else DocumentModel.Kind.TEXT
        )
        document = model
        ReaderPlayer.setDocument(model, 0)

        b.parseProgress.visibility = View.VISIBLE
        b.parseProgress.isIndeterminate = true

        if (kind == DocumentLoader.Kind.PDF) setupPdf(uri, model) else setupText(model, uri)
        maybeShowHint()
    }

    private fun setupText(model: DocumentModel, uri: Uri) {
        val adapter = ParagraphAdapter { sentenceIndex -> onTapSentence(sentenceIndex) }
        adapter.textSizeSp = Settings.fontSize.toFloat()
        adapter.highlightColor = highlightColor()
        adapter.attach(model)
        textAdapter = adapter
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.setPadding(dp(18), dp(8), dp(18), dp(24))
        b.list.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurface))
        b.list.adapter = adapter

        parseThread = Thread({
            DocumentLoader.loadText(this, uri, sink(model))
        }, "xw-parse").also { it.start() }
    }

    private fun setupPdf(uri: Uri, model: DocumentModel) {
        val source = runCatching { PdfSource.open(this, uri, uri.toString()) }.getOrElse {
            Toast.makeText(this, getString(R.string.parse_error, it.message ?: ""), Toast.LENGTH_LONG).show()
            finish(); return
        }
        pdfSource = source
        Library.updateProgress(this, uri.toString(), 0, 0, source.pageCount)

        val adapter = PdfPageAdapter(source, model) { page, x, y -> onTapPdf(page, x, y) }
        adapter.highlightColor = highlightColor()
        pdfAdapter = adapter
        b.list.layoutManager = LinearLayoutManager(this)
        b.list.setPadding(dp(6), dp(6), dp(6), dp(24))
        b.list.setBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceVariant))
        b.list.adapter = adapter

        parseThread = Thread({
            runCatching { PdfTextExtractor.extract(source.file, sink(model)) }
                .onFailure { e -> ui.post { showParseError(e.message ?: "") } }
        }, "xw-pdf").also { it.start() }
    }

    private fun sink(model: DocumentModel) = object : DocumentLoader.Sink {
        override fun onBatch(paragraphs: List<ParsedParagraph>) {
            model.addBatch(paragraphs)
            ui.post {
                if (document !== model) return@post
                textAdapter?.syncCount()
                maybeSwitchVoiceForLanguage(model)
                applyPendingStart(model)
                maybeAutoPlay(model)
                updateStatus()
            }
        }

        override fun onProgress(parsed: Int, total: Int) {
            model.parsedUnits = parsed
            model.totalUnits = total
            ui.post {
                if (document !== model) return@post
                if (total > 0) {
                    b.parseProgress.isIndeterminate = false
                    b.parseProgress.max = total
                    b.parseProgress.progress = parsed
                }
                updateSubtitle()
            }
        }

        override fun onDone() {
            model.markComplete()
            ui.post {
                if (document !== model) return@post
                b.parseProgress.visibility = View.GONE
                textAdapter?.syncCount()
                maybeSwitchVoiceForLanguage(model)
                applyPendingStart(model)
                maybeOfferOcr(model)
                maybeAutoPlay(model)
                updateSubtitle()
                updateStatus()
            }
        }

        override fun onError(message: String) {
            ui.post { if (document === model) showParseError(message) }
        }
    }

    private fun showParseError(message: String) {
        b.parseProgress.visibility = View.GONE
        val text = if (message == "doc") getString(R.string.legacy_doc)
        else getString(R.string.parse_error, message)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun applyPendingStart(model: DocumentModel) {
        if (pendingStartIndex <= 0) return
        if (model.sentenceCount > pendingStartIndex) {
            ReaderPlayer.seekTo(pendingStartIndex, keepPlaying = ReaderPlayer.isPlaying)
            pendingStartIndex = -1
        }
    }

    private fun maybeAutoPlay(model: DocumentModel) {
        if (!autoPlayWhenReady || model.sentenceCount <= 0 || pendingStartIndex > 0) return
        if (!TtsEngine.isReady) {
            if (voiceLoading) return
            autoPlayWhenReady = false
            Toast.makeText(this, R.string.no_voice, Toast.LENGTH_LONG).show()
            showVoices()
            return
        }
        autoPlayWhenReady = false
        startPlayback()
    }

    // ------------------------------------------------------------------ denetimler

    private fun wireControls() {
        b.btnPlay.setOnClickListener { togglePlayback() }
        b.btnNext.setOnClickListener { ReaderPlayer.next() }
        b.btnPrev.setOnClickListener { ReaderPlayer.previous() }
        b.btnNextPar.setOnClickListener { ReaderPlayer.skipParagraphs(1) }
        b.btnPrevPar.setOnClickListener { ReaderPlayer.skipParagraphs(-1) }
        b.voiceButton.setOnClickListener { showVoices() }
        b.locateButton.setOnClickListener {
            followReading = true
            b.locateButton.visibility = View.GONE
            scrollToCurrent(force = true)
        }

        val speed = Math.round(Settings.speed.coerceIn(0.6f, 2.0f) / 0.05f) * 0.05f
        b.speedSlider.value = speed.coerceIn(0.6f, 2.0f)
        b.speedLabel.text = getString(R.string.speed_label, speed)
        ReaderPlayer.setSpeed(speed)
        b.speedSlider.addOnChangeListener { _, value, fromUser ->
            b.speedLabel.text = getString(R.string.speed_label, value)
            if (fromUser) {
                ReaderPlayer.setSpeed(value)
                Settings.speed = value
            }
        }

        b.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
                ReaderPlayer.seekTo(sb.progress)
                followReading = true
                b.locateButton.visibility = View.GONE
            }
        })

        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    followReading = false
                    b.locateButton.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun startPlayback() {
        if (!TtsEngine.isReady) {
            Toast.makeText(this, R.string.no_voice, Toast.LENGTH_SHORT).show()
            showVoices()
            return
        }
        PlayerService.send(this, PlayerService.ACTION_PLAY)
        updateControls()
    }

    private fun togglePlayback() {
        if (ReaderPlayer.isPlaying) PlayerService.send(this, PlayerService.ACTION_PAUSE)
        else startPlayback()
    }

    // ------------------------------------------------------------------ dokunuşlar

    /** Metin kipinde dokunuş. */
    private fun onTapSentence(sentenceIndex: Int) = handleTap(sentenceIndex)

    /** PDF kipinde dokunuş: sayfa noktasından cümle bulunur. */
    private fun onTapPdf(page: Int, x: Float, y: Float) {
        val doc = document ?: return
        val sentences = doc.sentencesOfPage(page)
        var best = -1
        var bestScore = Float.MAX_VALUE
        for (s in sentences) {
            for (box in s.boxes) {
                if (box.contains(x, y)) {
                    handleTap(s.index); return
                }
                val dx = when {
                    x < box.left -> box.left - x
                    x > box.right -> x - box.right
                    else -> 0f
                }
                val dy = when {
                    y < box.top -> box.top - y
                    y > box.bottom -> y - box.bottom
                    else -> 0f
                }
                val score = dy * 3f + dx
                if (score < bestScore) {
                    bestScore = score; best = s.index
                }
            }
        }
        // Boş bir yere dokunulduysa o sayfanın başından sürdür
        if (best < 0 || bestScore > 140f) best = doc.firstSentenceOfPage(page)
        handleTap(best)
    }

    private fun handleTap(sentenceIndex: Int) {
        val now = System.currentTimeMillis()
        if (now - lastTapAt > 900) tapCount = 0
        lastTapAt = now
        tapCount++
        if (sentenceIndex >= 0) pendingSentence = sentenceIndex

        ui.removeCallbacks(tapRunnable)
        if (tapCount >= 3) {
            tapCount = 0
            val target = pendingSentence
            if (target >= 0) {
                followReading = true
                b.locateButton.visibility = View.GONE
                ReaderPlayer.seekTo(target, keepPlaying = true)
                startPlayback()
                showBars()
            }
        } else {
            ui.postDelayed(tapRunnable, 320)
        }
    }

    private fun toggleBars() = if (barsVisible) hideBars() else showBars()

    private fun showBars() {
        barsVisible = true
        b.appBar.visibility = View.VISIBLE
        b.controls.visibility = View.VISIBLE
    }

    private fun hideBars() {
        barsVisible = false
        b.appBar.visibility = View.GONE
        b.controls.visibility = View.GONE
    }

    // ------------------------------------------------------------------ ses çubuğu

    private fun showVoices() {
        VoiceSheet.show(this) { refreshVoiceBar() }
    }

    private fun refreshVoiceBar() {
        val voice = TtsEngine.voice
        b.voiceButton.text = voice?.let { "${it.flag} · ${it.displayName}" }
            ?: getString(R.string.pick_voice)

        b.speakerGroup.removeAllViews()
        if (voice != null && voice.numSpeakers > 1) {
            b.speakerGroup.visibility = View.VISIBLE
            voice.speakerNames.forEachIndexed { index, name ->
                val button = MaterialButton(
                    this, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle
                ).apply {
                    text = name
                    isCheckable = true
                    id = View.generateViewId()
                    tag = index
                    minWidth = 0
                    minimumWidth = 0
                    insetTop = 0
                    insetBottom = 0
                }
                b.speakerGroup.addView(button)
                if (index == ReaderPlayer.speakerId) b.speakerGroup.check(button.id)
            }
            b.speakerGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
                if (!isChecked) return@addOnButtonCheckedListener
                val index = group.findViewById<MaterialButton>(checkedId)?.tag as? Int ?: return@addOnButtonCheckedListener
                if (index == ReaderPlayer.speakerId) return@addOnButtonCheckedListener
                ReaderPlayer.setSpeaker(index)
                TtsEngine.voice?.let { Settings.setSpeakerFor(it.id, index) }
                updateSubtitle()
            }
        } else {
            b.speakerGroup.visibility = View.GONE
        }
        updateSubtitle()
    }

    // ------------------------------------------------------------------ güncelleme

    private fun updateControls() {
        b.btnPlay.setImageResource(if (ReaderPlayer.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        b.btnPlay.contentDescription = getString(if (ReaderPlayer.isPlaying) R.string.pause else R.string.play)
    }

    private fun updateSubtitle() {
        val doc = document
        val parts = ArrayList<String>()
        if (doc != null && doc.kind == DocumentModel.Kind.PDF) {
            val page = (doc.sentenceAt(ReaderPlayer.currentIndex)?.page ?: -1) + 1
            val total = pdfSource?.pageCount ?: 0
            if (page > 0 && total > 0) parts.add(getString(R.string.page_of, page, total))
        }
        if (doc != null && !doc.isComplete) {
            parts.add(
                if (doc.totalUnits > 0) getString(R.string.parsing, doc.parsedUnits, doc.totalUnits)
                else getString(R.string.parsing_unknown)
            )
        }
        supportActionBar?.subtitle = parts.joinToString(" · ")
    }

    private fun updateStatus() {
        val doc = document
        val total = doc?.sentenceCount ?: 0
        val index = ReaderPlayer.currentIndex
        if (total > 0) {
            val pct = ((index + 1) * 100 / total).coerceIn(0, 100)
            b.statusLeft.text = getString(R.string.status_line, index + 1, total, pct)
            if (!seeking) {
                b.seek.max = (total - 1).coerceAtLeast(0)
                b.seek.progress = index.coerceIn(0, b.seek.max)
            }
        } else {
            b.statusLeft.text = ""
        }

        b.statusRight.text = when {
            ReaderPlayer.isPreparing && ReaderPlayer.isPlaying -> getString(R.string.preparing)
            else -> {
                val secs = ReaderPlayer.remainingSeconds()
                val ahead = ReaderPlayer.readyAhead
                val parts = ArrayList<String>()
                if (secs > 0) parts.add(getString(R.string.remaining, formatDuration(secs)))
                if (ahead > 0) parts.add(getString(R.string.buffered, ahead))
                parts.joinToString(" · ")
            }
        }
        b.nowReading.text = doc?.sentenceAt(index)?.text ?: ""
    }

    private fun formatDuration(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return when {
            h > 0 -> getString(R.string.dur_hm, h, m)
            m > 0 -> getString(R.string.dur_m, m)
            else -> getString(R.string.dur_s, s)
        }
    }

    private fun scrollToCurrent(force: Boolean = false) {
        val doc = document ?: return
        val index = ReaderPlayer.currentIndex
        val lm = b.list.layoutManager as? LinearLayoutManager ?: return

        if (doc.kind == DocumentModel.Kind.PDF) {
            val sentence = doc.sentenceAt(index) ?: return
            val page = sentence.page
            if (page < 0) return
            val size = pdfSource?.pageSizes?.getOrNull(page) ?: return
            val viewWidth = (b.list.width - b.list.paddingLeft - b.list.paddingRight).toFloat()
            if (viewWidth <= 0) return
            val scale = viewWidth / size[0]
            val top = (sentence.boxes.firstOrNull()?.top ?: 0f) * scale
            val target = (b.list.height / 3f).coerceAtLeast(0f)
            if (!force) {
                val holder = b.list.findViewHolderForAdapterPosition(page)
                if (holder != null) {
                    val yOnScreen = holder.itemView.top + top
                    if (yOnScreen > b.list.height * 0.12f && yOnScreen < b.list.height * 0.78f) return
                }
            }
            lm.scrollToPositionWithOffset(page, -(top - target).toInt())
        } else {
            val pos = textAdapter?.paragraphOf(index) ?: return
            if (pos < 0) return
            if (!force) {
                val first = lm.findFirstCompletelyVisibleItemPosition()
                val last = lm.findLastCompletelyVisibleItemPosition()
                if (pos in first..last) return
            }
            lm.scrollToPositionWithOffset(pos, b.list.height / 3)
        }
    }

    private fun saveProgress() {
        if (ReaderPlayer.isPlaying) {
            flushListenTime()
            playingSince = SystemClock.elapsedRealtime()
        } else flushListenTime()
        val uri = documentUri ?: return
        val doc = document ?: return
        val index = ReaderPlayer.currentIndex
        val finished = doc.isComplete && index >= doc.sentenceCount - 1
        Library.updateProgress(
            this, uri.toString(), if (finished) 0 else index, doc.sentenceCount,
            pdfSource?.pageCount ?: -1
        )
    }

    private fun applyKeepScreenOn() {
        if (Settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun highlightColor(): Int = ContextCompat.getColor(
        this, when (Settings.highlightColor) {
            1 -> R.color.hl_yellow
            2 -> R.color.hl_green
            3 -> R.color.hl_pink
            else -> R.color.hl_blue
        }
    )

    private fun showFontDialog() {
        val doc = document
        if (doc?.kind == DocumentModel.Kind.PDF) {
            Toast.makeText(this, R.string.font_pdf_note, Toast.LENGTH_SHORT).show()
            return
        }
        val sizes = (14..30 step 2).toList()
        val labels = sizes.map { "$it sp" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_font)
            .setSingleChoiceItems(labels, sizes.indexOf(Settings.fontSize).coerceAtLeast(0)) { d, which ->
                Settings.fontSize = sizes[which]
                textAdapter?.textSizeSp = sizes[which].toFloat()
                d.dismiss()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun themeColor(attr: Int): Int {
        val typed = android.util.TypedValue()
        theme.resolveAttribute(attr, typed, true)
        return typed.data
    }

    /** Uc dokunus ipucunu yalnizca ilk belgede goster. */
    private fun maybeShowHint() {
        if (Settings.hintShown) return
        Settings.hintShown = true
        ui.postDelayed({
            if (!isFinishing) Toast.makeText(this, R.string.triple_tap_hint, Toast.LENGTH_LONG).show()
        }, 1500)
    }

    // ------------------------------------------------------------------ oynatıcı olayları

    override fun onSentenceChanged(index: Int) {
        ui.post {
            textAdapter?.setCurrentSentence(index)
            pdfAdapter?.setCurrentSentence(index)
            if (followReading && Settings.autoScroll) scrollToCurrent()
            updateStatus()
            updateSubtitle()
            if (index % 20 == 0) saveProgress()
        }
    }

    override fun onStateChanged(playing: Boolean, preparing: Boolean) {
        if (playing) playingSince = SystemClock.elapsedRealtime() else flushListenTime()
        ui.post {
            updateControls()
            updateStatus()
        }
    }

    /** Dinlenen süreyi kitaplığa yazar ("en çok okunanlar" bunun üzerinden sıralanır). */
    private fun flushListenTime() {
        val started = playingSince
        playingSince = 0L
        if (started <= 0L) return
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000
        val uri = documentUri ?: return
        if (seconds > 0) Library.addListenTime(this, uri.toString(), seconds)
    }

    override fun onFinished() {
        ui.post {
            Toast.makeText(this, R.string.finished, Toast.LENGTH_SHORT).show()
            updateControls()
        }
    }

    override fun onError(message: String) {
        ui.post { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_URI = "uri"

        fun open(ctx: Context, uri: String) {
            ctx.startActivity(
                Intent(ctx, ReaderActivity::class.java).putExtra(EXTRA_URI, uri)
            )
        }
    }
}
