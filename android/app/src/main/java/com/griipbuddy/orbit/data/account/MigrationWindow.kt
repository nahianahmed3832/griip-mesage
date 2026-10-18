package com.griipbuddy.orbit.data.account

/**
 * The server's migration window for legacy accounts (docs/ORBIT_2_ARCHITECTURE §5): whether the move to an
 * email-and-password account is offered ([open]), and whether a recognised installation may put it off ("Later").
 * Unit-tested in `MigrationWindowTest`.
 */
data class MigrationWindow(val open: Boolean, val canWait: Boolean) {
    companion object {
        /**
         * Offered only while the server can email codes ([AccountsInfo.registration]): the move ends with one, so
         * offering it without email would leave people stuck there. It can wait while the old sign-in works, unless the
         * server requires the move.
         */
        fun of(features: List<String>?, accounts: AccountsInfo?): MigrationWindow {
            val open = features.orEmpty().contains("legacy-migration") && accounts?.registration != false
            val required = open && accounts?.legacyMigrationRequired == true
            return MigrationWindow(open = open, canWait = accounts?.legacyLogin != false && !required)
        }
    }
}
