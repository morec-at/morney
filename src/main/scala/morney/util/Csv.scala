package morney.util

object Csv {
  def header(columns: List[String]): String =
    columns.map(escape).mkString(",") + "\n"

  def row(values: List[String]): String =
    values.map(escape).mkString(",") + "\n"

  private def escape(value: String): String = {
    val needsQuotes =
      value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')
    val escaped = value.replace("\"", "\"\"")
    if (needsQuotes) s""""$escaped"""" else escaped
  }
}

