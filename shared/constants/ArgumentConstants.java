package shared.constants;

public class ArgumentConstants {
    public static final String PORT = "-port";
    public static final String LOSS = "-loss";
    public static final String SEMANTICS = "-semantics";

    public enum Semantics {
        AT_LEAST_ONCE("at-least-once"),
        AT_MOST_ONCE("at-most-once");

        public final String value;

        Semantics(String value) {
            this.value = value;
        }

        public String getValue() {
            return this.value;
        }

        public static Semantics fromString(String text) {
            for (Semantics s : Semantics.values()) {
                if (s.value.equalsIgnoreCase(text)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("Illegal semantics argument: " + text);
        }
    }
}
