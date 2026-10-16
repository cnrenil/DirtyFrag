package df.root;

/**
 * Prefs keys for the KMI / vermagic selection.
 *
 * These used to live on KmiInfoActivity, which no longer exists as a screen:
 * the KMI controls were folded into the status tab. [ExploitRunner] reads them
 * from Java and the UI writes them, so they need a home that is not tied to an
 * Activity.
 */
public final class KmiPrefs {

    private KmiPrefs() {}

    public static final String PREFS_NAME = "kmi_info_display";
    public static final String SELECTION = "display_branch";
    public static final String VERMAGIC_MODE = "vermagic_mode";
    public static final String VERMAGIC_CUSTOM = "vermagic_custom";

    public static final String MODE_BUILTIN = "builtin";
    public static final String MODE_VIVO = "vivo";
    public static final String MODE_ANDROID = "android";
    public static final String MODE_CUSTOM = "custom";
}
