package com.frammy.unitylauncher.upgrades.config.types;

import org.bukkit.configuration.file.FileConfiguration;

import static com.frammy.unitylauncher.upgrades.config.CfgIO.def;

public record LibraryCfg(
        ScrollsCfg scrolls,
        CalmCfg calm,
        EducationCfg education
) {
    public static LibraryCfg defaults() {
        return new LibraryCfg(
                ScrollsCfg.defaults(),
                CalmCfg.defaults(),
                EducationCfg.defaults()
        );
    }

    public static boolean addDefaults(FileConfiguration c) {
        boolean dirty = false;

        dirty |= def(c, "library.scrolls.enabled", ScrollsCfg.defaults().enabled());
        dirty |= def(c, "library.scrolls.permBase", ScrollsCfg.defaults().permBase());
        dirty |= def(c, "library.scrolls.refundChancePercent", ScrollsCfg.defaults().refundChancePercent());

        dirty |= def(c, "library.calm.enabled", CalmCfg.defaults().enabled());
        dirty |= def(c, "library.calm.permBase", CalmCfg.defaults().permBase());
        dirty |= def(c, "library.calm.chanceToCancelHunger", CalmCfg.defaults().chanceToCancelHunger());
        dirty |= def(c, "library.calm.antispamMs", CalmCfg.defaults().antispamMs());

        dirty |= def(c, "library.education.enabled", EducationCfg.defaults().enabled());
        dirty |= def(c, "library.education.permBase", EducationCfg.defaults().permBase());
        dirty |= def(c, "library.education.rewardMultiplier", EducationCfg.defaults().rewardMultiplier());

        return dirty;
    }

    public static LibraryCfg read(FileConfiguration c) {
        var d = defaults();

        return new LibraryCfg(
                new ScrollsCfg(
                        c.getBoolean("library.scrolls.enabled", d.scrolls().enabled()),
                        c.getString("library.scrolls.permBase", d.scrolls().permBase()),
                        c.getDouble("library.scrolls.refundChancePercent", d.scrolls().refundChancePercent())
                ),
                new CalmCfg(
                        c.getBoolean("library.calm.enabled", d.calm().enabled()),
                        c.getString("library.calm.permBase", d.calm().permBase()),
                        c.getDouble("library.calm.chanceToCancelHunger", d.calm().chanceToCancelHunger()),
                        c.getLong("library.calm.antispamMs", d.calm().antispamMs())
                ),
                new EducationCfg(
                        c.getBoolean("library.education.enabled", d.education().enabled()),
                        c.getString("library.education.permBase", d.education().permBase()),
                        c.getDouble("library.education.rewardMultiplier", d.education().rewardMultiplier())
                )
        );
    }

    public record ScrollsCfg(
            boolean enabled,
            String permBase,
            double refundChancePercent
    ) {
        public static ScrollsCfg defaults() {
            return new ScrollsCfg(
                    true,
                    "unity.library.scrolls",
                    25
            );
        }
    }

    public record CalmCfg(
            boolean enabled,
            String permBase,
            double chanceToCancelHunger,
            long antispamMs
    ) {
        public static CalmCfg defaults() {
            return new CalmCfg(
                    true,
                    "unity.library.calm",
                    0.25,
                    750L
            );
        }
    }

    public record EducationCfg(
            boolean enabled,
            String permBase,
            double rewardMultiplier
    ) {
        public static EducationCfg defaults() {
            return new EducationCfg(
                    true,
                    "unity.library.education",
                    1.25
            );
        }
    }
}
