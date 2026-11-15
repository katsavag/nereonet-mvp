package com.nereonix.nereonet_mvp.core.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nereonix.nereonet_mvp.core.ai.OnnxOcr.Decoded;

/**
 * P67: the parts of the local OCR that need no model: the characters from an inference.yml (every value as text) or a
 * dict.txt, CTC decoding (blanks, repeats, the space after the list) and the regions of a probability map.
 */
class OnnxOcrTest {

    @TempDir
    Path dir;

    @Test
    void theCharactersOfAnInferenceYmlAreAllText() throws Exception {
        Path yml = dir.resolve("rec.yml");
        Files.writeString(yml, """
                Global:
                  model_name: el_PP-OCRv5_mobile_rec
                PostProcess:
                  name: CTCLabelDecode
                  character_dict:
                  - '0'
                  - '1'
                  - y
                  - n
                  - ~
                  - '#'
                  - Ω
                """, StandardCharsets.UTF_8);

        assertThat(OnnxOcr.characters(yml)).containsExactly("0", "1", "y", "n", "~", "#", "Ω");
    }

    @Test
    void theCharactersOfADictTxt() throws Exception {
        Path txt = dir.resolve("dict.txt");
        Files.writeString(txt, "a\r\nb\nΩ\n", StandardCharsets.UTF_8);

        assertThat(OnnxOcr.characters(txt)).containsExactly("a", "b", "Ω");
    }

    @Test
    void ctcDecoding() {
        List<String> characters = List.of("1", "2", "a");
        // Classes: 0 blank, 1 "1", 2 "2", 3 "a", 4 the space after the list.
        float[][] steps = {
                {0.1f, 0.9f, 0, 0, 0},   // 1
                {0.2f, 0.8f, 0, 0, 0},   // 1 again: the same character
                {0.9f, 0.1f, 0, 0, 0},   // blank
                {0.3f, 0.7f, 0, 0, 0},   // 1: a new one after the blank
                {0, 0, 0, 0, 1.0f},      // space
                {0, 0, 0, 0.6f, 0.4f},   // a
        };

        Decoded decoded = OnnxOcr.decode(steps, characters);

        assertThat(decoded.text()).isEqualTo("11 a");
        assertThat(decoded.confidence()).isCloseTo((0.9 + 0.7 + 1.0 + 0.6) / 4, within(1e-6));
        assertThat(OnnxOcr.decode(new float[][]{{1, 0}}, characters).text()).isEmpty();
    }

    @Test
    void regionsOfTheProbabilityMap() {
        float[][] map = new float[20][40];
        // A sure word at x 2-12, y 2-6; a doubtful one at x 20-30, y 10-14; a speck at (35, 18).
        fill(map, 2, 2, 12, 6, 0.9f);
        fill(map, 20, 10, 30, 14, 0.4f);
        map[18][35] = 1f;

        List<double[]> regions = OnnxOcr.regions(map, 0.3f, 0.6);

        assertThat(regions).hasSize(1);
        assertThat(regions.getFirst()).containsExactly(2, 2, 12, 6);
        assertThat(OnnxOcr.regions(map, 0.3f, 0.3)).hasSize(2);
    }

    private static void fill(float[][] map, int x0, int y0, int x1, int y1, float value) {
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                map[y][x] = value;
            }
        }
    }
}
