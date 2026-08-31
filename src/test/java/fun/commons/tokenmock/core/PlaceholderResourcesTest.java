package fun.commons.tokenmock.core;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceholderResourcesTest {

    private final PlaceholderResources ph = new PlaceholderResources();

    @Test
    void silence_mp3_is_non_empty_bytes() {
        byte[] mp3 = ph.silenceMp3();
        assertThat(mp3).isNotEmpty();
        // ID3 tag header
        assertThat(mp3[0]).isEqualTo((byte) 'I');
        assertThat(mp3[1]).isEqualTo((byte) 'D');
        assertThat(mp3[2]).isEqualTo((byte) '3');
    }

    @Test
    void placeholder_png_is_valid_png_signature() {
        byte[] png = ph.placeholderPng();
        assertThat(png).hasSizeGreaterThan(8);
        // PNG magic bytes: 89 50 4E 47 0D 0A 1A 0A
        assertThat(png[0]).isEqualTo((byte) 0x89);
        assertThat(png[1]).isEqualTo((byte) 'P');
        assertThat(png[2]).isEqualTo((byte) 'N');
        assertThat(png[3]).isEqualTo((byte) 'G');
    }

    @Test
    void placeholder_mp4_is_non_empty_with_ftyp_box() {
        byte[] mp4 = ph.placeholderMp4();
        assertThat(mp4.length).isGreaterThan(16);
        // bytes 4-7 should be 'ftyp'
        assertThat(mp4[4]).isEqualTo((byte) 'f');
        assertThat(mp4[5]).isEqualTo((byte) 't');
        assertThat(mp4[6]).isEqualTo((byte) 'y');
        assertThat(mp4[7]).isEqualTo((byte) 'p');
    }

    @Test
    void same_instance_returned_caches_resource() {
        byte[] mp3a = ph.silenceMp3();
        byte[] mp3b = ph.silenceMp3();
        assertThat(mp3a).isSameAs(mp3b);
    }
}
