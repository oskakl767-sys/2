package com.mdm.agent.data.model

import java.io.File

sealed class CollectedData {
    data class JsonResult(val json: String) : CollectedData()
    data class FileResult(val file: File, val metadata: String = "") : CollectedData()
    data class TextResult(val text: String) : CollectedData()
    /**
     * Special marker: the command has already sent its response(s) via sendInfoUpdate()
     * or sendResponse(). handleCommand should NOT send any additional response.
     *
     * Used by screenshot-on: it sends status="info" immediately, then the final
     * success/error is sent by ScreenCapturePermissionActivity after user approves.
     */
    object PendingResult : CollectedData()
}