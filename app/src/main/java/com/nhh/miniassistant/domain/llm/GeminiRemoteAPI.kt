package com.nhh.miniassistant.domain.llm

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.provider.CalendarContract
import java.util.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.nhh.miniassistant.data.RetrievedContext
import com.nhh.miniassistant.data.ChunksDB
import com.nhh.miniassistant.data.DocumentsDB
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.Content
import com.google.ai.client.generativeai.type.FunctionDeclaration
import com.google.ai.client.generativeai.type.GenerationConfig
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.Schema
import com.google.ai.client.generativeai.type.Tool
import com.nhh.miniassistant.R
import com.nhh.miniassistant.data.LlmResult
import com.nhh.miniassistant.domain.SentenceEmbeddingProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class GeminiRemoteAPI(
    private val apiKey: String,
    private val launchIntent: (Intent) -> Unit,
    private val appContext: Context? = null,
    private val documentsDB: DocumentsDB,
    private val chunksDB: ChunksDB,
    private val sentenceEncoder: SentenceEmbeddingProvider
) : LLMInferenceAPI() {
    private val generativeModel: GenerativeModel

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun checkNumDocuments(): Boolean = documentsDB.getDocsCount() > 0

    private data class DateParts(
        val year: Int, val month: Int, val day: Int,
        val hour: Int, val minute: Int
    )

    private fun parseDate(jsonStr: String?): DateParts? {
        if (jsonStr.isNullOrBlank()) return null
        val jo = JSONObject(jsonStr)
        val date = DateParts(
            year = jo.optInt("year", Int.MIN_VALUE),
            month = jo.optInt("month", Int.MIN_VALUE),
            day = jo.optInt("day", Int.MIN_VALUE),
            hour = jo.optInt("hour", Int.MIN_VALUE),
            minute = jo.optInt("minute", Int.MIN_VALUE)
        )
        return date
    }

    private fun parseAttendees(csv: String?): List<String>? =
        csv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }

    private val ragRetriever = FunctionDeclaration(
        name = "ragRetriever",
        description = "Retrieve information from documents uploaded by the user",
        parameters = listOf(
            Schema.str("query", "User query to retrieve with"),
            Schema.int("topK", "Number of passages to retrieve (1-20)")
        ),
        requiredParameters = listOf("query")
    )

    private val createCall = FunctionDeclaration(
        name = "createCall",
        description = "Dial and create a phone call",
        parameters = listOf(
            Schema.str("tel", "Phone number to call")
        ),
        requiredParameters = listOf("tel")
    )

    private val createEmail = FunctionDeclaration(
        name = "createEmail",
        description = "Create an email",
        parameters = listOf(
            Schema.str("to", "Email address of the recipient"),
            Schema.str("subject", "Subject of the email"),
            Schema.str("body", "Body of the email")
        ),
        requiredParameters = listOf("to", "subject", "body")
    )

    private val createCalendar = FunctionDeclaration(
        name = "createCalendar",
        description = "Create a calendar event",
        parameters = listOf(
            Schema.str("title", "Title of the event"),
            Schema.str("description", "Description of the event"),
            Schema.str("address", "Location of the event"),
            Schema.str("start", """Start time as JSON string. Example: {"year":2025,"month":9,"day":7,"hour":14,"minute":0}. Month in 0-based (0 = Jan, 11 = Dec). """),
            Schema.str("end",   """End time as JSON string. Example: {"year":2025,"month":9,"day":7,"hour":15,"minute":0}. Month in 0-based (0 = Jan, 11 = Dec)"""),
            Schema.str("attendees", """Comma-separated emails. Example: "alice@example.com,bob@example.com" """)
        ),
        requiredParameters = listOf("title", "start", "end")
    )

    private val createNote = FunctionDeclaration(
        name = "createNote",
        description = "Create a note on Google Keep",
        parameters = listOf(
            Schema.str("title", "Title of the note"),
            Schema.str("body", "Body of the note"),
        ),
        requiredParameters = listOf("title", "body")
    )

    private fun ragRetriever(query: String, topK: Int): String {
        if (!checkNumDocuments()) {
            showToast("Add documents to execute queries")
            return "Add documents to execute queries"
        }
        var jointContext = ""
        val retrievedContextList = ArrayList<RetrievedContext>()
        val queryEmbedding = sentenceEncoder.encodeText(query)
        chunksDB.getSimilarChunks(queryEmbedding, n = topK).forEach {
            jointContext += " " + it.second.chunkData
            retrievedContextList.add(
                RetrievedContext(
                    it.second.docFileName,
                    it.second.chunkData,
                ),
            )
        }
        return jointContext
    }

    private fun createCall(tel: String?): String {
        if (tel.isNullOrBlank()) {
            return "Số điện thoại không hợp lệ: $tel"
        }
        val uriString = if (tel.startsWith("tel:")) tel else "tel:$tel"

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED) {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse(uriString)
            }
            maybeLaunch(intent)
            return "Đang gọi đến $tel"
        } else {
            return "Không thể thực hiện cuộc gọi vì chưa được cấp quyền."
        }
    }

    private fun createEmail(to: String, subject: String?, body: String?): String {
        val intent = Intent(Intent.ACTION_SEND)
        intent.setType("message/rfc822")
        intent.putExtra(Intent.EXTRA_EMAIL, arrayOf<String>(to))
        intent.putExtra(Intent.EXTRA_SUBJECT, subject)
        intent.putExtra(Intent.EXTRA_TEXT, body)
        intent.setPackage("com.google.android.gm")
        val pm: PackageManager = appContext!!.packageManager
        if (intent.resolveActivity(appContext.packageManager) != null) {
            maybeLaunch(intent)
            return "Đã gửi email đến $to"
        } else {
            return "Không thể gửi email."
        }
    }

    private fun createCalendar(title: String, description: String?, address: String?, start: DateParts, end: DateParts, attendees: List<String>?): String {
        val startCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, start.year)
            set(Calendar.MONTH, start.month)
            set(Calendar.DAY_OF_MONTH, start.day)
            set(Calendar.HOUR_OF_DAY, start.hour)
            set(Calendar.MINUTE, start.minute)
        }
        val endCal = Calendar.getInstance().apply {
            set(Calendar.YEAR, end.year)
            set(Calendar.MONTH, end.month)
            set(Calendar.DAY_OF_MONTH, end.day)
            set(Calendar.HOUR_OF_DAY, end.hour)
            set(Calendar.MINUTE, end.minute)
        }
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, title)
            putExtra(CalendarContract.Events.DESCRIPTION, description ?: "")
            putExtra(CalendarContract.Events.EVENT_LOCATION, address ?: "")
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startCal.timeInMillis)
            putExtra(CalendarContract.EXTRA_EVENT_END_TIME, endCal.timeInMillis)
            putExtra(Intent.EXTRA_EMAIL, attendees?.joinToString(",") ?: "")
        }
        maybeLaunch(intent)
        return "Đã thêm sự kiện $title"
    }

    private fun createNote(title: String, body: String): String {
        val intent = Intent(Intent.ACTION_SEND)
        intent.setType("text/plain")
        intent.setPackage("com.google.android.keep")

        intent.putExtra(Intent.EXTRA_SUBJECT, title)
        intent.putExtra(Intent.EXTRA_TEXT, body)

        maybeLaunch(intent)
        return "Đã tạo ghi chú $title vào Google Keep"
    }

    private fun maybeLaunch(intent: Intent) {
        if (appContext != null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchIntent(intent)
    }

    init {
        // Here's a good reference on topK, topP and temperature
        // parameters, which are used to control the output of a LLM
        // See
        // https://ivibudh.medium.com/a-guide-to-controlling-llm-model-output-exploring-top-k-top-p-and-temperature-parameters-ed6a31313910
        val configBuilder = GenerationConfig.Builder()
        configBuilder.topP = 0.4f
        configBuilder.temperature = 0.3f
        generativeModel =
            GenerativeModel(
                modelName = "gemini-2.5-flash",
                apiKey = apiKey,
                generationConfig = configBuilder.build(),
                tools = listOf(
                    Tool(
                        listOf(
                            createCall,
                            ragRetriever,
                            createEmail,
                            createCalendar,
                            createNote
                        )
                    )
                ),
                systemInstruction =
                    content {
                        text(
                            "You are a helpful and reliable assistant. You will be provided with a set of tools and a user query. Your job is to:" +
                                    "- Carefully read and understand the user's request and context before taking any action." +
                                    "- Decide whether tool usage is necessary. Only call a tool if it is essential to complete the user's request." +
                                    "- After the tool execution (if any), generate a complete and meaningful final response based on the tool’s output and notify when done."
                        )
                    },
            )
    }

    override suspend fun getResponse(state: String, query: String): LlmResult? =
        withContext(Dispatchers.IO) {
            val statePrompt = appContext?.getString(R.string.states)?.replace("\$HISTORY", state) ?: ""
            val queryPrompt = appContext?.getString(R.string.query)?.replace("\$QUERY", query) ?: ""
            var toolOutput = ""
            val firstPrompt = listOf(statePrompt, queryPrompt).joinToString("\n")
            Log.d("prompt", firstPrompt)
            val first = generativeModel.generateContent(firstPrompt)
            if(first.functionCalls.isEmpty()) return@withContext LlmResult(response = first.text ?: "Đã xảy ra lỗi.", toolOutput = toolOutput)
            for (fc in first.functionCalls) {
                try {
                    when (fc.name) {
                        "ragRetriever" -> {
                            val q = fc.args["query"].orEmpty()
                            val k = (fc.args["topK"]?.toInt() ?: 5).coerceIn(1, 20)
                            val funcRes = ragRetriever(q, k)
                            toolOutput = toolOutput + "${fc.name}: $funcRes\n"
                        }
                        "createCall" -> {
                            val tel = fc.args["tel"]
                            val funcRes = createCall(tel)
                            toolOutput = toolOutput + "${fc.name}: $funcRes\n"
                        }
                        "createCalendar" -> {
                            val title = fc.args["title"].orEmpty()
                            val description = fc.args["description"].orEmpty()
                            val address = fc.args["address"].orEmpty()
                            val start = parseDate(fc.args["start"]) ?: DateParts(
                                Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH,
                                Calendar.HOUR_OF_DAY, Calendar.MINUTE
                            )
                            val end = parseDate(fc.args["end"]) ?: DateParts(
                                Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH,
                                Calendar.HOUR_OF_DAY, Calendar.MINUTE
                            )
                            val attendees = parseAttendees(fc.args["attendees"]) ?: emptyList()
                            val funcRes = createCalendar(title, description, address, start, end, attendees)
                            toolOutput = toolOutput + "${fc.name}: $funcRes\n"
                        }
                        "createEmail" -> {
                            val to = fc.args["to"].orEmpty()
                            val subject = fc.args["subject"].orEmpty()
                            val body = fc.args["body"].orEmpty()
                            val funcRes = createEmail(to, subject, body)
                            toolOutput = toolOutput + "${fc.name}: $funcRes\n"
                        }
                        "createNote" -> {
                            val title = fc.args["title"].orEmpty()
                            val body = fc.args["body"].orEmpty()
                            val funcRes = createNote(title, body)
                            toolOutput = toolOutput + "${fc.name}: $funcRes\n"
                        }
                    }
                } catch (e: Exception) {
                    toolOutput = toolOutput + "Error when calling ${fc.name}: $e"
                }
            }

            val toolPrompt = appContext?.getString(R.string.tools)?.replace("\$TOOL_OUTPUT", toolOutput) ?: ""
            val finalPrompt = listOf(statePrompt, queryPrompt, toolPrompt).joinToString("\n")
            Log.d("prompt", finalPrompt)
            val final = generativeModel.generateContent(finalPrompt)
            LlmResult(response = final.text ?: "Đã xảy ra lỗi.", toolOutput = toolOutput)
        }
}
