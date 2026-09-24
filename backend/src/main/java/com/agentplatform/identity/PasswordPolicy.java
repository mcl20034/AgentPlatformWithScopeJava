package com.agentplatform.identity;

import com.agentplatform.common.BusinessException;

public final class PasswordPolicy {
    private PasswordPolicy() {}

    public static void validate(String password) {
        boolean valid = password != null && password.length() >= 12 && password.length() <= 72
                && password.chars().anyMatch(Character::isUpperCase)
                && password.chars().anyMatch(Character::isLowerCase)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c));
        if (!valid) {
            throw new BusinessException(422, "PASSWORD_POLICY_VIOLATION", "密码需为 12～72 位，并同时包含大写字母、小写字母、数字和特殊字符");
        }
    }
}
