package fun.commons.tokenmock.core;

import org.springframework.stereotype.Component;

import java.util.HexFormat;

/**
 * In-memory binary placeholders for audio / image / video responses.
 * <p>
 * All files are minimal but valid-format bytes so the client can verify
 * Content-Type without needing actual media codecs.
 */
@Component
public class PlaceholderResources {

    private final byte[] silenceMp3 = buildSilenceMp3();
    private final byte[] placeholderPng = buildPlaceholderPng();
    private final byte[] placeholderMp4 = buildPlaceholderMp4();

    public byte[] silenceMp3() {
        return silenceMp3;
    }

    public byte[] placeholderPng() {
        return placeholderPng;
    }

    public byte[] placeholderMp4() {
        return placeholderMp4;
    }

    /**
     * Minimal MP3: ID3v2 header + a few silent frames.
     * <p>
     * Format:
     *   "ID3" + version (0x03 0x00) + flags (0x00) + size (4 synchsafe bytes = 0)
     *   followed by MPEG frame header (0xFF 0xFB 0x90 0x64) + zero data
     */
    private byte[] buildSilenceMp3() {
        byte[] header = new byte[]{
                'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64
        };
        // silence frame body (~128kbps MP3 frame is 417 bytes total)
        byte[] frame = new byte[413];
        byte[] all = new byte[header.length + frame.length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(frame, 0, all, header.length, frame.length);
        return all;
    }

    /**
     * Minimal valid PNG: 1x1 transparent pixel.
     */
    private byte[] buildPlaceholderPng() {
        String hex = "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d49444154789c63000100000005000100";
        hex += "0d0a3b3a0000000049454e44ae426082";
        return HexFormat.of().parseHex(hex);
    }

    /**
     * Minimal valid MP4: ftyp box header only.
     */
    private byte[] buildPlaceholderMp4() {
        byte[] ftyp = new byte[]{
                // box size (24 bytes)
                0x00, 0x00, 0x00, 0x18,
                // box type
                'f', 't', 'y', 'p',
                // major brand
                'i', 's', 'o', 'm',
                // minor version
                0x00, 0x00, 0x02, 0x00,
                // compatible brand
                'i', 's', 'o', 'm',
                // free box size (8)
                0x00, 0x00, 0x00, 0x08,
                // free box type
                'f', 'r', 'e', 'e'
        };
        return ftyp;
    }
}
