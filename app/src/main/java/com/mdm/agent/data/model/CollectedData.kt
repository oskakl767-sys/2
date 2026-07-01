package com.mdm.agent.data.model

import java.io.File

sealed class CollectedData {
    data class JsonResult(val json: String) : CollectedData()
    data class FileResult(val file: File, val metadata: String = "") : CollectedData()
    data class TextResult(val text: String) : CollectedData()
}