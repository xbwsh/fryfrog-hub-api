import java.sql.*;

public class DbCheck {
    public static void main(String[] args) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection c = DriverManager.getConnection("jdbc:sqlite:fryfrog.db");
        Statement s = c.createStatement();

        System.out.println("=== ebooks columns ===");
        ResultSet rs = s.executeQuery("PRAGMA table_info(ebooks)");
        while (rs.next()) {
            System.out.println(rs.getString(2));
        }

        System.out.println("\n=== ebooks data ===");
        rs = s.executeQuery("SELECT id, title, volume, cover_art_path, bangumi_id FROM ebooks ORDER BY id");
        while (rs.next()) {
            String cover = rs.getString(4);
            System.out.println("id=" + rs.getInt(1) + " | vol=" + rs.getString(3) + " | cover=" + (cover == null ? "NULL" : cover) + " | bangumiId=" + rs.getString(5));
        }

        System.out.println("\n=== media_series (ebook) ===");
        rs = s.executeQuery("SELECT id, title, cover_art_path FROM media_series WHERE media_type='ebook'");
        while (rs.next()) {
            String cover = rs.getString(3);
            System.out.println("id=" + rs.getInt(1) + " | title=" + rs.getString(2) + " | cover=" + (cover == null ? "NULL" : cover));
        }

        System.out.println("\n=== media_series (comic) ===");
        rs = s.executeQuery("SELECT id, title, cover_art_path FROM media_series WHERE media_type='comic'");
        while (rs.next()) {
            String cover = rs.getString(3);
            System.out.println("id=" + rs.getInt(1) + " | title=" + rs.getString(2) + " | cover=" + (cover == null ? "NULL" : cover));
        }
        c.close();
    }
}
