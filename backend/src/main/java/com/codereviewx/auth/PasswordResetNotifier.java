package com.codereviewx.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Delivers password-reset links. Replace the default bean with an e-mail implementation in production. */
public interface PasswordResetNotifier {
    void sendResetLink(User user, String link);

    /**
     * Default: records that a reset was requested, without the link or token, because a log is not a secure
     * delivery channel. In development the token is returned by the API instead (crx.auth.expose-reset-token).
     */
    @Component
    class LoggingOnly implements PasswordResetNotifier {
        private static final Logger log = LoggerFactory.getLogger(PasswordResetNotifier.class);

        @Override
        public void sendResetLink(User user, String link) {
            log.info("Password reset requested for user id={} (no e-mail delivery configured)", user.getId());
        }
    }
}
