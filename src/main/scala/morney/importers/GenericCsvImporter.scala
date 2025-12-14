package morney.importers

import zio.Chunk

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

final case class CsvMapping(
  dateColumn: String,
  amountColumn: String,
  memoColumn: Option[String],
  dedupColumn: Option[String]
)

final case class ImportedRow(
  date: LocalDate,
  amount: Long,
  memo: Option[String],
  dedupKeyCandidate: Option[String],
  raw: Map[String, String]
)

object GenericCsvImporter {
  def parse(csv: String, mapping: CsvMapping): Either[String, Chunk[ImportedRow]] =
    for {
      parsed <- parseWithHeader(csv)
      (header, rows) = parsed
      dateIdx <- colIndex(header, mapping.dateColumn)
      amountIdx <- colIndex(header, mapping.amountColumn)
      memoIdx <- mapping.memoColumn.map(colIndex(header, _)).fold(Right(Option.empty[Int]))(_.map(Some(_)))
      dedupIdx <- mapping.dedupColumn.map(colIndex(header, _)).fold(Right(Option.empty[Int]))(_.map(Some(_)))
      imported <- parseRows(header, rows, dateIdx, amountIdx, memoIdx, dedupIdx)
    } yield imported

  private def parseRows(
    header: Chunk[String],
    rows: Chunk[Chunk[String]],
    dateIdx: Int,
    amountIdx: Int,
    memoIdx: Option[Int],
    dedupIdx: Option[Int]
  ): Either[String, Chunk[ImportedRow]] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[ImportedRow]
    var rowNo = 1 // header is row 0
    val it = rows.iterator
    while (it.hasNext) {
      rowNo += 1
      val cols = it.next()
      val rawMap = header.zipAll(cols, "", "").toMap
      val dateStr = getAt(cols, dateIdx)
      val amountStr = getAt(cols, amountIdx)
      val memoStr = memoIdx.map(i => getAt(cols, i)).map(_.trim).filter(_.nonEmpty)
      val dedupStr = dedupIdx.map(i => getAt(cols, i)).map(_.trim).filter(_.nonEmpty)

      val parsedDate =
        try Right(parseDate(dateStr))
        catch { case t: Throwable => Left(s"Row $rowNo: invalid date '$dateStr' (${t.getMessage})") }
      val parsedAmount =
        try Right(parseAmount(amountStr))
        catch { case t: Throwable => Left(s"Row $rowNo: invalid amount '$amountStr' (${t.getMessage})") }

      val parsed =
        for {
          date <- parsedDate
          amount <- parsedAmount
        } yield ImportedRow(
          date = date,
          amount = amount,
          memo = memoStr,
          dedupKeyCandidate = dedupStr,
          raw = rawMap
        )

      parsed match {
        case Right(r) => out += r
        case Left(e)  => return Left(e)
      }
    }
    Right(Chunk.fromIterable(out))
  }

  private def colIndex(header: Chunk[String], columnName: String): Either[String, Int] = {
    val idx = header.indexWhere(h => h.trim.equalsIgnoreCase(columnName.trim))
    if (idx >= 0) Right(idx) else Left(s"CSV column not found: $columnName")
  }

  private def getAt(cols: Chunk[String], idx: Int): String =
    if (idx >= 0 && idx < cols.length) cols(idx) else ""

  private def parseDate(value: String): LocalDate = {
    val v = value.trim
    if (v.contains("/")) LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy/M/d").withLocale(Locale.ROOT))
    else LocalDate.parse(v) // ISO-8601 yyyy-MM-dd
  }

  private def parseAmount(value: String): Long = {
    val v0 = value.trim
    val negativeByParens = v0.startsWith("(") && v0.endsWith(")")
    val v1 = v0.stripPrefix("(").stripSuffix(")")
    val v2 = v1.replace(",", "").replace("¥", "").replace("￥", "").replace(" ", "")
    val n = v2.toLong
    if (negativeByParens) -n else n
  }

  private def parseWithHeader(csv: String): Either[String, (Chunk[String], Chunk[Chunk[String]])] = {
    val rows = parseCsv(csv)
    if (rows.isEmpty) Left("CSV is empty")
    else {
      val header = rows.head.map(_.trim)
      val data = rows.drop(1).filter(cols => cols.exists(_.trim.nonEmpty))
      Right((header, data))
    }
  }

  private def parseCsv(csv: String): Chunk[Chunk[String]] = {
    val normalized = csv.replace("\r\n", "\n").replace("\r", "\n")
    val lines = normalized.split('\n').toList
    Chunk.fromIterable(lines.filter(_.nonEmpty).map(parseCsvLine))
  }

  // Minimal CSV line parser supporting quotes and escaped quotes ("")
  private def parseCsvLine(line: String): Chunk[String] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[String]
    val sb = new StringBuilder
    var inQuotes = false
    var i = 0
    while (i < line.length) {
      val ch = line.charAt(i)
      if (inQuotes) {
        if (ch == '"') {
          val nextIsQuote = i + 1 < line.length && line.charAt(i + 1) == '"'
          if (nextIsQuote) {
            sb.append('"')
            i += 1
          } else {
            inQuotes = false
          }
        } else {
          sb.append(ch)
        }
      } else {
        ch match {
          case ',' =>
            out += sb.result()
            sb.clear()
          case '"' =>
            inQuotes = true
          case other =>
            sb.append(other)
        }
      }
      i += 1
    }
    out += sb.result()
    Chunk.fromIterable(out.toList)
  }
}
