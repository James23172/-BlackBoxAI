package inspection.auth.model;

public enum Role {
    CONFIGURATOR("configurator"),
    OPERATOR("operator"),
    ANALYST("analyst");

    private final String name;
    Role(String name) { this.name = name; }
    public String getRoleName() { return name; }

    public static Role fromString(String s) {
        if (s == null) return null;
        for (Role r : values()) {
            if (r.name.equalsIgnoreCase(s)) return r;
        }
        return null;
    }
}
