package top.harcochen.dsh;

import com.intellij.DynamicBundle;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.PropertyKey;

public final class DshBundle {

    @NonNls
    private static final String BUNDLE = "messages.DshBundle";
    private static final DynamicBundle INSTANCE = new DynamicBundle(DshBundle.class, BUNDLE);

    private DshBundle() {}

    @NotNull
    @Nls
    public static String message(@NotNull @PropertyKey(resourceBundle = BUNDLE) String key, Object @NotNull ... params) {
        return INSTANCE.getMessage(key, params);
    }
}
