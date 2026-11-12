package top.harcochen.dsh;

import com.intellij.diff.comparison.ComparisonManager;
import com.intellij.diff.comparison.ComparisonPolicy;
import com.intellij.diff.fragments.LineFragment;
import com.intellij.openapi.progress.EmptyProgressIndicator;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/** Stable line blocks in one captured text comparison, preserving exact working-tree bytes. */
final class DshReviewHunks {
    private static final int MAX_BYTES = 1024 * 1024;

    record Block(
            int index,
            int beforeLine,
            int beforeLines,
            int afterLine,
            int afterLines,
            String before,
            String after) {}

    record Plan(String before, String after, List<LineFragment> fragments) {
        List<Block> remaining(Set<Integer> reverted) {
            java.util.ArrayList<Block> blocks = new java.util.ArrayList<>();
            for (int index = 0; index < fragments.size(); index++) {
                if (reverted.contains(index)) continue;
                LineFragment fragment = fragments.get(index);
                blocks.add(
                        new Block(
                                index,
                                fragment.getStartLine1() + 1,
                                fragment.getEndLine1() - fragment.getStartLine1(),
                                fragment.getStartLine2() + 1,
                                fragment.getEndLine2() - fragment.getStartLine2(),
                                before.substring(
                                        fragment.getStartOffset1(), fragment.getEndOffset1()),
                                after.substring(
                                        fragment.getStartOffset2(), fragment.getEndOffset2())));
            }
            return List.copyOf(blocks);
        }

        byte[] content(Set<Integer> reverted) {
            List<String> beforeLines = java.util.Arrays.asList(before.split("\n", -1));
            List<String> afterLines = java.util.Arrays.asList(after.split("\n", -1));
            List<String> result = new java.util.ArrayList<>();
            int cursor = 0;
            for (int index = 0; index < fragments.size(); index++) {
                if (!reverted.contains(index)) continue;
                LineFragment fragment = fragments.get(index);
                result.addAll(afterLines.subList(cursor, fragment.getStartLine2()));
                result.addAll(
                        beforeLines.subList(fragment.getStartLine1(), fragment.getEndLine1()));
                cursor = fragment.getEndLine2();
            }
            result.addAll(afterLines.subList(cursor, afterLines.size()));
            return String.join("\n", result).getBytes(StandardCharsets.UTF_8);
        }
    }

    static Plan compare(byte[] beforeBytes, byte[] afterBytes) throws Exception {
        String before = text(beforeBytes);
        String after = text(afterBytes);
        List<LineFragment> fragments =
                ComparisonManager.getInstance()
                        .compareLines(
                                before,
                                after,
                                ComparisonPolicy.DEFAULT,
                                new EmptyProgressIndicator());
        Plan plan = new Plan(before, after, List.copyOf(fragments));
        Set<Integer> all = new java.util.LinkedHashSet<>();
        for (int index = 0; index < fragments.size(); index++) all.add(index);
        if (!java.util.Arrays.equals(beforeBytes, plan.content(all)))
            throw new IllegalStateException(DshBundle.message("dsh.review.hunks.unsupported"));
        return plan;
    }

    private static String text(byte[] bytes) throws Exception {
        if (bytes.length > MAX_BYTES)
            throw new IllegalStateException(DshBundle.message("dsh.files.too.large"));
        String text;
        try {
            text =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalStateException(DshBundle.message("dsh.files.binary"));
        }
        if (text.indexOf('\0') >= 0)
            throw new IllegalStateException(DshBundle.message("dsh.files.binary"));
        return text;
    }

    private DshReviewHunks() {}
}
