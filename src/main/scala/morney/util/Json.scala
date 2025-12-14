package morney.util

import java.time.Instant
import java.time.format.DateTimeFormatter

object Json {
  def str(value: String): String = s""""${escape(value)}""""

  def num(value: Long): String = value.toString

  def bool(value: Boolean): String = if (value) "true" else "false"

  def instant(value: Instant): String = str(DateTimeFormatter.ISO_INSTANT.format(value))

  def obj(fields: List[(String, String)]): String =
    fields.map { case (k, v) => s""""${escape(k)}":$v""" }.mkString("{", ",", "}")

  def arr(values: Iterable[String]): String =
    values.mkString("[", ",", "]")

  def opt(value: Option[String]): String =
    value match {
      case Some(v) => str(v)
      case None    => "null"
    }

  private def escape(s: String): String =
    s.flatMap {
      case '"'  => "\\\""
      case '\\' => "\\\\"
      case '\b' => "\\b"
      case '\f' => "\\f"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c.isControl => "\\u%04x".format(c.toInt)
      case c => c.toString
    }
}

