package top.harcochen.dsh;

import java.math.BigInteger;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Exact SemVer compatibility; package selectors must be resolved by the launcher first. */
final class DshRuntimeVersion {
    static final String DEFAULT = "0.1.5-rc.2";
    static final String MINIMUM = "0.1.5-rc.1";
    private static final Pattern VERSION =
            Pattern.compile(
                    "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
                            + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?");

    private DshRuntimeVersion() {}

    static String exact(String value) {
        if (value == null || !VERSION.matcher(value).matches()) return null;
        String pre =
                value.split("\\+", 2)[0].split("-", 2).length == 2
                        ? value.split("\\+", 2)[0].split("-", 2)[1]
                        : "";
        for (String part : pre.split("\\.")) {
            if (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")) return null;
        }
        return value;
    }

    static String fromOutput(String output) {
        for (String line : output.split("\\R")) {
            String value =
                    line.strip()
                            .replaceFirst("^(?:dsh|@deepseek-ai/dsh)\\s+v?", "")
                            .replaceFirst("^v", "");
            if (exact(value) != null) return value;
        }
        return null;
    }

    static boolean compatible(String value) {
        return exact(value) != null && compare(value, MINIMUM) >= 0;
    }

    private static int compare(String left, String right) {
        Matcher a = VERSION.matcher(left), b = VERSION.matcher(right);
        a.matches();
        b.matches();
        for (int index = 1; index <= 3; index++) {
            int compared = new BigInteger(a.group(index)).compareTo(new BigInteger(b.group(index)));
            if (compared != 0) return compared;
        }
        String ap = a.group(4), bp = b.group(4);
        if (ap == null || bp == null) return ap == null ? bp == null ? 0 : 1 : -1;
        List<String> aa = List.of(ap.split("\\.")), bb = List.of(bp.split("\\."));
        for (int index = 0; index < Math.min(aa.size(), bb.size()); index++) {
            String x = aa.get(index), y = bb.get(index);
            boolean xn = x.matches("[0-9]+"), yn = y.matches("[0-9]+");
            int compared =
                    xn && yn
                            ? new BigInteger(x).compareTo(new BigInteger(y))
                            : xn != yn ? xn ? -1 : 1 : x.compareTo(y);
            if (compared != 0) return compared;
        }
        return Integer.compare(aa.size(), bb.size());
    }
}
