package com.xwendevan.reader.audio

import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.MPGLib
import java.io.Closeable
import java.io.OutputStream

/**
 * Piper'ın ürettiği mono float örnekleri MP3'e yazar.
 *
 * Kodlayıcı jump3r'dir: LAME'in saf Java portu, yerel kütüphane gerektirmez.
 * Örnekler parça parça verilebilir, böylece uzun metinlerde tüm ses belleğe
 * alınmadan dosyaya akıtılır.
 */
class Mp3Writer(
    sampleRate: Int,
    bitrateKbps: Int,
    private val out: OutputStream
) : Closeable {

    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private var closed = false

    /** LAME'in istediği tampon: 1,25 × örnek + 7200 bayt. */
    private val block = 8192
    private val mp3Buf = ByteArray((block * 1.25).toInt() + 7200)
    private val pcm = IntArray(block)

    init {
        val ga = GainAnalysis()
        val bs = BitStream()
        val presets = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val vbr = VBRTag()
        val ver = Version()
        val id3 = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val mpg = MPGLib()

        lame.setModules(ga, bs, presets, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        presets.setModules(lame)
        qu.setModules(bs, reservoir, qupvt, takehiro)
        qupvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bs)
        takehiro.setModules(qupvt)
        vbr.setModules(lame, bs, ver)

        gfp = lame.lame_init()
        gfp.num_channels = 1
        gfp.in_samplerate = sampleRate
        gfp.brate = bitrateKbps
        gfp.mode = MPEGMode.MONO
        gfp.quality = 3
        gfp.write_id3tag_automatic = false
        if (lame.lame_init_params(gfp) < 0) {
            throw IllegalStateException("MP3 kodlayıcı başlatılamadı")
        }
    }

    /** Mono örnekler (-1..1). */
    fun write(samples: FloatArray) {
        var offset = 0
        while (offset < samples.size) {
            val n = minOf(block, samples.size - offset)
            for (i in 0 until n) {
                // lame_encode_buffer_int tam 32 bitlik ölçek bekler
                val s = samples[offset + i].coerceIn(-1f, 1f)
                pcm[i] = (s * 2147483647.0).toInt()
            }
            val written = lame.lame_encode_buffer_int(gfp, pcm, pcm, n, mp3Buf, 0, mp3Buf.size)
            if (written < 0) throw IllegalStateException("MP3 kodlama hatası: $written")
            if (written > 0) out.write(mp3Buf, 0, written)
            offset += n
        }
    }

    /** Son çerçeveleri boşaltır. Akış kapatılmaz. */
    fun finish() {
        if (closed) return
        closed = true
        val written = lame.lame_encode_flush(gfp, mp3Buf, 0, mp3Buf.size)
        if (written > 0) out.write(mp3Buf, 0, written)
        out.flush()
        lame.lame_close(gfp)
    }

    override fun close() {
        runCatching { finish() }
    }
}
