import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

allprojects {
    group = "com.company.uiinspector"
    version = "0.1.0." + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmm"))
}

