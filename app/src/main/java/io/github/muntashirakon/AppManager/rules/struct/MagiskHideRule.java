// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.muntashirakon.AppManager.rules.struct;

import androidx.annotation.NonNull;

import java.util.StringTokenizer;

import io.github.muntashirakon.AppManager.rules.RulesStorageManager;

public class MagiskHideRule extends RuleEntry {
    private boolean isHidden;

    public MagiskHideRule(@NonNull String packageName, boolean isHidden) {
        super(packageName, STUB, RulesStorageManager.Type.MAGISK_HIDE);
        this.isHidden = isHidden;
    }

    public MagiskHideRule(@NonNull String packageName, @NonNull StringTokenizer tokenizer)
            throws IllegalArgumentException {
        super(packageName, STUB, RulesStorageManager.Type.MAGISK_HIDE);
        if (tokenizer.hasMoreElements()) {
            isHidden = Boolean.parseBoolean(tokenizer.nextElement().toString());
        } else throw new IllegalArgumentException("Invalid format: isHidden not found");
    }

    public boolean isHidden() {
        return isHidden;
    }

    public void setHidden(boolean hidden) {
        isHidden = hidden;
    }

    @NonNull
    @Override
    public String toString() {
        return "MagiskHideRule{" +
                "packageName='" + packageName + '\'' +
                ", isHidden=" + isHidden +
                '}';
    }

    @NonNull
    @Override
    public String flattenToString(boolean isExternal) {
        return addPackageWithTab(isExternal) + name + "\t" + type.name() + "\t" + isHidden;
    }
}
