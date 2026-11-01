package cl.streambox.tv;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.common.MimeTypes;

import org.junit.Test;

public final class DecoderInfoTest {
    @Test
    public void namesTheChipVendor() {
        assertEquals("MediaTek", DecoderInfo.vendor("OMX.MTK.VIDEO.DECODER.AVC"));
        assertEquals("MediaTek", DecoderInfo.vendor("c2.mtk.avc.decoder"));
        assertEquals("Amlogic", DecoderInfo.vendor("OMX.amlogic.avc.decoder.awesome2"));
        assertEquals("Realtek", DecoderInfo.vendor("OMX.realtek.video.decoder"));
        assertEquals("Qualcomm", DecoderInfo.vendor("c2.qti.avc.decoder"));
        assertEquals("Android", DecoderInfo.vendor("c2.android.aac.decoder"));
        assertEquals("Emulador", DecoderInfo.vendor("c2.goldfish.h264.decoder"));
        assertEquals("", DecoderInfo.vendor(""));
    }

    @Test
    public void recognisesSoftwareDecoders() {
        assertTrue(DecoderInfo.looksSoftware("c2.android.aac.decoder"));
        assertTrue(DecoderInfo.looksSoftware("OMX.google.h264.decoder"));
        assertTrue(DecoderInfo.looksSoftware("ffmpegLib"));
        assertFalse(DecoderInfo.looksSoftware("OMX.MTK.VIDEO.DECODER.AVC"));
    }

    @Test
    public void labelHasKindVendorAndTechnicalName() {
        assertEquals("Hardware · MediaTek\nOMX.MTK.VIDEO.DECODER.AVC",
                DecoderInfo.label("OMX.MTK.VIDEO.DECODER.AVC", true, "Hardware", "Software"));
        assertEquals("Software · Android\nc2.android.aac.decoder",
                DecoderInfo.label("c2.android.aac.decoder", false, "Hardware", "Software"));
        assertEquals("", DecoderInfo.label("", true, "Hardware", "Software"));
    }

    @Test
    public void dolbyGoesToTheTv() {
        assertTrue(DecoderInfo.isPassthroughMime(MimeTypes.AUDIO_AC3));
        assertTrue(DecoderInfo.isPassthroughMime(MimeTypes.AUDIO_E_AC3));
        assertFalse(DecoderInfo.isPassthroughMime(MimeTypes.AUDIO_AAC));
    }
}
