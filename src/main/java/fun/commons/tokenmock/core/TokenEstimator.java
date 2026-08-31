package fun.commons.tokenmock.core;

import org.springframework.stereotype.Component;

@Component
public class TokenEstimator {

    /**
     * Rough token estimation:
     * - ASCII text: char_count / 4
     * - CJK chars (chinese/japanese/korean): counted directly (≈ 1 token each)
     * - Whitespace: ignored
     */
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int ascii = 0;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (isCjk(c)) {
                cjk++;
            } else {
                ascii++;
            }
        }
        return cjk + Math.max(1, ascii / 4);
    }

    public int total(int promptTokens, int completionTokens) {
        return promptTokens + completionTokens;
    }

    private boolean isCjk(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }
}
