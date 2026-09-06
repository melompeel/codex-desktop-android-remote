package com.alphapi.codexremote

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationNavigationTest {
    @Test
    fun userInputNotificationsAreDistinguishedFromApprovalCards() {
        assertEquals(
            RemoteNotificationKind.USER_INPUT,
            notificationKindForApproval("item/tool/requestUserInput"),
        )
        assertEquals(
            RemoteNotificationKind.APPROVAL,
            notificationKindForApproval("item/commandExecution/requestApproval"),
        )
    }
}
