import java.sql.*;

public class DbCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection c = DriverManager.getConnection("jdbc:sqlite:fryfrog.db");
        Statement s = c.createStatement();

        System.out.println("=== media_series columns ===");
        ResultSet rs = s.executeQuery("PRAGMA table_info(media_series)");
        while (rs.next()) {
            System.out.println(rs.getString(2) + " | " + rs.getString(3));
        }

        System.out.println("\n=== media_series data ===");
        rs = s.executeQuery("SELECT * FROM media_series");
        ResultSetMetaData meta = rs.getMetaData();
        int cols = meta.getColumnCount();
        while (rs.next()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= cols; i++) {
                if (i > 1) sb.append(" | ");
                sb.append(meta.getColumnName(i)).append("=").append(rs.getString(i));
            }
            System.out.println(sb);
        }
        c.close();
    }
}
