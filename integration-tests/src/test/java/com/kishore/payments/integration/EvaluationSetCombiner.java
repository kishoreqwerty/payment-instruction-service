package com.kishore.payments.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The final step of evaluation-set generation (.notes/reports/PHASE-11-REPORT.md section 4): reads
 * both {@link EvaluationSetGeneratorPartA} and {@link EvaluationSetGeneratorPartB}'s intermediate
 * {@code _partA.jsonl}/{@code _partB.jsonl}, shuffles with a fixed seed (reproducible), and splits
 * 140 train / 60 held-out -- committing to that split here, once, is what makes the held-out
 * number mean anything later (phase brief section 4: "the held-out set is touched only for final
 * evaluation"). No containers, no services: pure file I/O, which is why this is a separate,
 * fast, no-infrastructure step rather than folded into either generator part.
 */
@Tag("generator")
class EvaluationSetCombiner {

    private static final int TRAIN_SIZE = 140;
    private static final int HOLDOUT_SIZE = 60;

    @Test
    void combineAndSplit() throws Exception {
        Path labelsDir = Path.of("..", "evaluation", "labels");
        List<String> partA = Files.readAllLines(labelsDir.resolve("_partA.jsonl"));
        List<String> partB = Files.readAllLines(labelsDir.resolve("_partB.jsonl"));

        List<String> all = new ArrayList<>();
        all.addAll(partA);
        all.addAll(partB);
        assertThat(all).hasSize(TRAIN_SIZE + HOLDOUT_SIZE);

        Collections.shuffle(all, new Random(42));
        List<String> train = all.subList(0, TRAIN_SIZE);
        List<String> holdout = all.subList(TRAIN_SIZE, TRAIN_SIZE + HOLDOUT_SIZE);

        Files.write(labelsDir.resolve("train.jsonl"), train, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Files.write(labelsDir.resolve("holdout.jsonl"), holdout, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        Files.deleteIfExists(labelsDir.resolve("_partA.jsonl"));
        Files.deleteIfExists(labelsDir.resolve("_partB.jsonl"));

        System.out.println("Wrote " + train.size() + " train cases and " + holdout.size() + " holdout cases");
    }
}
