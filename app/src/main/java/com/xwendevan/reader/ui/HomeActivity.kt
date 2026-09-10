package com.xwendevan.reader.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.xwendevan.reader.R
import com.xwendevan.reader.data.BookEntry
import com.xwendevan.reader.data.Library
import com.xwendevan.reader.databinding.ActivityHomeBinding
import com.xwendevan.reader.doc.DocumentLoader
import com.xwendevan.reader.tts.TtsEngine

/** Ana ekran: eklenen belgelerin kitaplığı. */
class HomeActivity : AppCompatActivity() {

    private lateinit var b: ActivityHomeBinding
    private lateinit var adapter: BookAdapter
    private lateinit var continueAdapter: BookAdapter
    private lateinit var favoriteAdapter: BookAdapter
    private lateinit var mostReadAdapter: BookAdapter
    private val ui = Handler(Looper.getMainLooper())

    private val openDocLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) addDocument(uri)
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        setSupportActionBar(b.toolbar)

        adapter = newAdapter(compact = false)
        b.list.layoutManager = GridLayoutManager(this, spanCount())
        b.list.adapter = adapter

        continueAdapter = newAdapter(compact = true)
        favoriteAdapter = newAdapter(compact = true)
        mostReadAdapter = newAdapter(compact = true)
        setupShelf(b.listContinue, continueAdapter)
        setupShelf(b.listFavorites, favoriteAdapter)
        setupShelf(b.listMostRead, mostReadAdapter)

        b.addButton.setOnClickListener { pickDocument() }
        b.emptyAdd.setOnClickListener { pickDocument() }

        askNotificationPermission()

        // Ses modelini erkenden hazırla
        VoiceSheet.loadSaved(this) { updateVoiceLine() }

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        updateVoiceLine()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.action_about -> startActivity(Intent(this, AboutActivity::class.java))
            R.id.action_speak -> startActivity(Intent(this, SpeakActivity::class.java))
            R.id.action_voices -> VoiceSheet.show(this) { updateVoiceLine() }

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    // ------------------------------------------------------------------

    private fun handleIncomingIntent(intent: Intent?) {
        // Dosya değil de düz metin paylaşıldıysa "Metin okut" ekranına gönder
        if (intent?.action == Intent.ACTION_SEND &&
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) == null &&
            !intent.getStringExtra(Intent.EXTRA_TEXT).isNullOrBlank()
        ) {
            startActivity(
                Intent(this, SpeakActivity::class.java)
                    .setAction(Intent.ACTION_SEND)
                    .putExtra(Intent.EXTRA_TEXT, intent.getStringExtra(Intent.EXTRA_TEXT))
            )
            return
        }

        val uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        } ?: return
        addDocument(uri, open = true)
    }

    private fun pickDocument() {
        openDocLauncher.launch(
            arrayOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/epub+zip",
                "text/plain",
                "text/*",
                "*/*"
            )
        )
    }

    private fun addDocument(uri: Uri, open: Boolean = true) {
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val name = DocumentLoader.displayName(this, uri)
        val kind = when (DocumentLoader.kindOf(this, uri)) {
            DocumentLoader.Kind.PDF -> "pdf"
            DocumentLoader.Kind.DOCX -> "docx"
            DocumentLoader.Kind.EPUB -> "epub"
            DocumentLoader.Kind.LEGACY_DOC -> "doc"
            else -> "txt"
        }
        if (kind == "doc") {
            AlertDialog.Builder(this)
                .setMessage(R.string.legacy_doc)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        Library.touch(this, uri.toString(), name, kind)
        if (kind == "pdf") {
            Thread {
                val path = Library.makeThumb(this, uri, uri.toString())
                if (path != null) {
                    Library.setThumb(this, uri.toString(), path)
                    ui.post { refresh() }
                }
            }.start()
        }
        refresh()
        if (open) ReaderActivity.open(this, uri.toString())
    }

    private fun newAdapter(compact: Boolean) = BookAdapter(
        onClick = { entry -> ReaderActivity.open(this, entry.uri) },
        onLongClick = { entry -> showBookMenu(entry) },
        compact = compact
    )

    private fun setupShelf(list: androidx.recyclerview.widget.RecyclerView, a: BookAdapter) {
        list.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        list.adapter = a
    }

    /** Karta basılı tutunca: favori ve kaldırma. */
    private fun showBookMenu(entry: BookEntry) {
        val favLabel = getString(if (entry.favorite) R.string.fav_remove else R.string.fav_add)
        val items = arrayOf(favLabel, getString(R.string.remove))
        AlertDialog.Builder(this)
            .setTitle(entry.name.substringBeforeLast('.'))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        Library.setFavorite(this, entry.uri, !entry.favorite)
                        refresh()
                    }

                    1 -> confirmRemove(entry.uri, entry.name)
                }
            }
            .show()
    }

    private fun confirmRemove(uri: String, name: String) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage(R.string.remove_book_q)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.remove) { _, _ ->
                Library.remove(this, uri)
                refresh()
            }
            .show()
    }

    private fun refresh() {
        val books = Library.all(this)
        adapter.submit(books)

        shelf(b.shelfContinue, continueAdapter, Library.continuing(this).take(12))
        shelf(b.shelfFavorites, favoriteAdapter, Library.favorites(this).take(12))
        shelf(b.shelfMostRead, mostReadAdapter, Library.mostRead(this).take(12))

        b.emptyView.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        b.scroll.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        b.allTitle.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun shelf(section: View, a: BookAdapter, items: List<BookEntry>) {
        a.submit(items)
        section.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateVoiceLine() {
        val voice = TtsEngine.voice
        b.voiceLine.text = voice?.let {
            val speaker = if (it.numSpeakers > 1) " · ${it.speakerNames.getOrNull(com.xwendevan.reader.player.ReaderPlayer.speakerId) ?: ""}" else ""
            getString(R.string.current_voice, "${it.flag} ${it.displayName}$speaker")
        } ?: getString(R.string.no_voice_short)
    }

    private fun spanCount(): Int {
        val widthDp = resources.configuration.screenWidthDp
        return (widthDp / 170).coerceIn(2, 5)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
