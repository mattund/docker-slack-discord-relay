package local.mdc.util.dockerslackrelay;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.DateFormat;
import java.util.Calendar;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

public class LineLogFormatter extends Formatter {
  private String lineSeparator = "\n";

  /**
   * Format the given LogRecord.
   * @param record the log record to be formatted.
   * @return a formatted log record
   */
  public synchronized String format(LogRecord record) {

    StringBuilder sb = new StringBuilder();

    String message = formatMessage(record);

    sb.append("[" + DateFormat.getDateTimeInstance(
            DateFormat.LONG, DateFormat.SHORT
    ).format(Calendar.getInstance().getTime()) + "] ");

    sb.append("[" + record.getLevel().getLocalizedName() + "] ");

    sb.append(message);
    sb.append(lineSeparator);
    if (record.getThrown() != null) {
      try {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        record.getThrown().printStackTrace(pw);
        pw.close();
        sb.append(sw.toString());
      } catch (Exception ex) {
      }
    }
    return sb.toString();
  }
}