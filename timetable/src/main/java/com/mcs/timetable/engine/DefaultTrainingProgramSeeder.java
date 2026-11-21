package com.mcs.timetable.engine;

import com.mcs.timetable.db.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

public class DefaultTrainingProgramSeeder {

    /**
     * Ensures the default Fall-2026 Training Program is present on first launch
     * without overwriting if the admin has already generated or modified it.
     */
    public static void ensureDefaultTimetableLoaded(String semesterTerm) {
        String countSql = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ?";
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn.prepareStatement(countSql)) {
            ps.setString(1, semesterTerm);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                // Seed initial default timetable using early-period priority
                new TimetableGenerator().generateForTerm(semesterTerm);
            }
        } catch (Exception ignored) {
            // Safe fallback: never crash on startup if DB is temporarily unreachable
        }
    }
}