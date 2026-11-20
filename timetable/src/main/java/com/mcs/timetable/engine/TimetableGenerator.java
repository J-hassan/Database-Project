package com.mcs.timetable.engine;

import com.mcs.timetable.db.DatabaseConnection;
import com.mcs.timetable.model.AllocationTask;
import com.mcs.timetable.model.TimeSlot;

import java.sql.*;
import java.util.*;

public class TimetableGenerator {

    private final Set<String> busySectionSlots = new HashSet<>();
    private final Set<String> busyInstructorSlots = new HashSet<>();
    private final Set<String> busyLESlots = new HashSet<>();
    private final Set<String> busyRoomSlots = new HashSet<>();
    private final Set<String> sectionCourseDays = new HashSet<>();

    public String generateForTerm(String semesterTerm) {
        String bestReport = "";
        for (int attempt = 1; attempt <= 15; attempt++) {
            GenerationResult res = runSingleAttempt(semesterTerm);
            bestReport = res.message;
            if (res.warningCount == 0) {
                return res.message + " (Resolved in attempt #" + attempt + ")";
            }
        }
        return bestReport;
    }

    private static class GenerationResult {
        int warningCount;
        String message;

        GenerationResult(int warningCount, String message) {
            this.warningCount = warningCount;
            this.message = message;
        }
    }

    private GenerationResult runSingleAttempt(String semesterTerm) {
        busySectionSlots.clear();
        busyInstructorSlots.clear();
        busyLESlots.clear();
        busyRoomSlots.clear();
        sectionCourseDays.clear();

        int scheduledCount = 0;
        List<String> warnings = new ArrayList<>();

        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement del = conn
                    .prepareStatement("DELETE FROM timetable_schedule WHERE semester_term = ?")) {
                del.setString(1, semesterTerm);
                del.executeUpdate();
            }

            List<TimeSlot> validSlots = loadValidMorningSlots(conn);
            Map<String, List<TimeSlot>> slotsByDay = groupSlotsByDay(validSlots);

            List<Integer> regularRooms = new ArrayList<>();
            regularRooms.addAll(loadRoomsByType(conn, "CLASSROOM", null));
            regularRooms.addAll(loadRoomsByType(conn, "LECTURE_HALL", null));

            List<Integer> generalSoftwareLabs = loadGeneralSoftwareLabs(conn);

            List<AllocationTask> tasks = loadAllocations(conn, semesterTerm);
            if (tasks.isEmpty()) {
                return new GenerationResult(1,
                        "No course allocations found for " + semesterTerm + ". Please add allocations first.");
            }
            tasks.sort((a, b) -> Integer.compare(b.labBlocksNeeded, a.labBlocksNeeded));

            String insertSQL = "INSERT INTO timetable_schedule " +
                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type, lab_group_tag) "
                    +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

            try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {

                // PHASE 1: SCHEDULE 3-CONSECUTIVE-SLOT LABS
                // Theory instructor is FREE during labs; only Section + Lab Engineer + Lab Room
                // must be free!
                for (AllocationTask task : tasks) {
                    if (task.labBlocksNeeded > 0) {
                        List<Integer> candidateLabs = new ArrayList<>(
                                loadRoomsByType(conn, "LAB", task.requiredLabSpecialty));
                        if (!"PHYSICS".equalsIgnoreCase(task.requiredLabSpecialty)) {
                            for (int labId : generalSoftwareLabs) {
                                if (!candidateLabs.contains(labId)) {
                                    candidateLabs.add(labId);
                                }
                            }
                        }

                        boolean placed = scheduleLabBlock(insertStmt, semesterTerm, task, slotsByDay, candidateLabs);
                        if (placed) {
                            scheduledCount += 3;
                        } else {
                            warnings.add("Lab conflict for " + task.sectionLabel + " - " + task.courseTitle);
                        }
                    }
                }

                // PHASE 2: SCHEDULE THEORY LECTURES
                for (AllocationTask task : tasks) {
                    for (int i = 0; i < task.theorySlotsNeeded; i++) {
                        boolean placed = scheduleSingleTheory(insertStmt, semesterTerm, task, validSlots, regularRooms,
                                false);
                        if (!placed) {
                            placed = scheduleSingleTheory(insertStmt, semesterTerm, task, validSlots, regularRooms,
                                    true);
                        }
                        if (placed) {
                            scheduledCount++;
                        } else {
                            warnings.add("Theory slot missing for " + task.sectionLabel + " - " + task.courseTitle);
                        }
                    }
                }

                // PHASE 3: SCHEDULE STI SLOTS
                for (AllocationTask task : tasks) {
                    for (int i = 0; i < task.stiSlotsNeeded; i++) {
                        boolean placed = scheduleSTI(insertStmt, semesterTerm, task, validSlots);
                        if (placed) {
                            scheduledCount++;
                        } else {
                            warnings.add("STI slot missing for " + task.sectionLabel + " - " + task.courseTitle);
                        }
                    }
                }

                insertStmt.executeBatch();
            }

            conn.commit();
            if (warnings.isEmpty()) {
                return new GenerationResult(0, "SUCCESS! Generated " + scheduledCount
                        + " conflict-free class/lab/STI slots across all sections with ZERO warnings.");
            } else {
                return new GenerationResult(warnings.size(), "Generated " + scheduledCount + " slots with "
                        + warnings.size() + " warnings:\n" + String.join("\n", warnings));
            }

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
            }
            if (e.getMessage() != null && e.getMessage().contains("uq_no_instructor_clash")) {
                return new GenerationResult(999,
                        "DB Schema Update Needed: Run 'ALTER TABLE timetable_schedule DROP INDEX uq_no_instructor_clash;' in MySQL Workbench so theory instructors can remain free during lab blocks.");
            }
            return new GenerationResult(999, "Database Error: " + e.getMessage());
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
            }
            return new GenerationResult(999, "Unexpected Error: " + e.getMessage());
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    // ========================================================================
    // 1. MID-SEMESTER INSTRUCTOR REPLACEMENT
    // ========================================================================
    public String replaceInstructor(String semesterTerm, int allocationId, int newInstructorId) {
        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            int sectionId = 0;
            String courseTitle = "";
            String allocSql = "SELECT ca.section_id, c.course_title FROM course_allocations ca " +
                    "JOIN courses c ON ca.course_id = c.course_id WHERE ca.allocation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(allocSql)) {
                ps.setInt(1, allocationId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    sectionId = rs.getInt("section_id");
                    courseTitle = rs.getString("course_title");
                } else {
                    return "ERROR: Allocation record not found.";
                }
            }

            try (PreparedStatement updAlloc = conn.prepareStatement(
                    "UPDATE course_allocations SET instructor_id = ? WHERE allocation_id = ?")) {
                updAlloc.setInt(1, newInstructorId);
                updAlloc.setInt(2, allocationId);
                updAlloc.executeUpdate();
            }

            try (PreparedStatement updLabInst = conn.prepareStatement(
                    "UPDATE timetable_schedule SET instructor_id = ? WHERE allocation_id = ? AND semester_term = ? AND session_type = 'LAB'")) {
                updLabInst.setInt(1, newInstructorId);
                updLabInst.setInt(2, allocationId);
                updLabInst.setString(3, semesterTerm);
                updLabInst.executeUpdate();
            }

            List<int[]> nonLabSlots = new ArrayList<>();
            List<String> nonLabTypes = new ArrayList<>();

            String fetchSched = "SELECT schedule_id, slot_id, room_id, session_type " +
                    "FROM timetable_schedule WHERE allocation_id = ? AND semester_term = ? AND session_type <> 'LAB'";
            try (PreparedStatement ps = conn.prepareStatement(fetchSched)) {
                ps.setInt(1, allocationId);
                ps.setString(2, semesterTerm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    int rId = rs.getInt("room_id");
                    if (rs.wasNull())
                        rId = 0;
                    nonLabSlots.add(new int[] { rs.getInt("schedule_id"), rs.getInt("slot_id"), rId });
                    nonLabTypes.add(rs.getString("session_type"));
                }
            }

            int directTransferred = 0;
            int rescheduledCount = 0;
            List<String> unresolved = new ArrayList<>();

            for (int i = 0; i < nonLabSlots.size(); i++) {
                int schedId = nonLabSlots.get(i)[0];
                int slotId = nonLabSlots.get(i)[1];
                String type = nonLabTypes.get(i);

                if (isInstructorFreeForTheoryOrMeeting(conn, semesterTerm, slotId, newInstructorId)) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE timetable_schedule SET instructor_id = ? WHERE schedule_id = ?")) {
                        upd.setInt(1, newInstructorId);
                        upd.setInt(2, schedId);
                        upd.executeUpdate();
                        directTransferred++;
                    }
                } else {
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM timetable_schedule WHERE schedule_id = ?")) {
                        del.setInt(1, schedId);
                        del.executeUpdate();
                    }
                    String movedSlot = relocateMasterSession(conn, semesterTerm, allocationId, sectionId,
                            newInstructorId, type);
                    if (movedSlot != null)
                        rescheduledCount++;
                    else
                        unresolved.add("Could not auto-relocate " + type + " slot");
                }
            }

            conn.commit();
            String summary = "Instructor Replacement Complete for " + courseTitle + ":\n" +
                    "• Direct Clash-Free Transfers: " + directTransferred + " theory/STI slots\n" +
                    "• Auto-Rescheduled (due to clash): " + rescheduledCount + " slots";
            if (!unresolved.isEmpty())
                summary += "\n• Warnings: " + String.join(", ", unresolved);
            return summary;

        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
            }
            return "Operation Failed: " + e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    // ========================================================================
    // 2. MID-SEMESTER LAB ENGINEER (LE) REPLACEMENT & REMOVAL
    // ========================================================================
    public String replaceLabEngineer(String semesterTerm, int allocationId, int newLeId) {
        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            int sectionId = 0, instructorId = 0;
            String courseTitle = "";
            String allocSql = "SELECT ca.section_id, ca.instructor_id, c.course_title FROM course_allocations ca " +
                    "JOIN courses c ON ca.course_id = c.course_id WHERE ca.allocation_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(allocSql)) {
                ps.setInt(1, allocationId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    sectionId = rs.getInt("section_id");
                    instructorId = rs.getInt("instructor_id");
                    courseTitle = rs.getString("course_title");
                } else {
                    return "ERROR: Allocation record not found.";
                }
            }

            try (PreparedStatement updAlloc = conn.prepareStatement(
                    "UPDATE course_allocations SET lab_engineer_id = ? WHERE allocation_id = ?")) {
                updAlloc.setInt(1, newLeId);
                updAlloc.setInt(2, allocationId);
                updAlloc.executeUpdate();
            }

            List<int[]> labSlots = new ArrayList<>();
            String fetchLabs = "SELECT schedule_id, slot_id, room_id FROM timetable_schedule " +
                    "WHERE allocation_id = ? AND semester_term = ? AND session_type = 'LAB' ORDER BY slot_id";
            try (PreparedStatement ps = conn.prepareStatement(fetchLabs)) {
                ps.setInt(1, allocationId);
                ps.setString(2, semesterTerm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    labSlots.add(new int[] { rs.getInt("schedule_id"), rs.getInt("slot_id"), rs.getInt("room_id") });
                }
            }

            if (labSlots.isEmpty()) {
                conn.commit();
                return "Lab Engineer updated in allocation record for " + courseTitle + ".";
            }

            boolean allFree = true;
            for (int[] ls : labSlots) {
                if (!isInstructorFreeForTheoryOrMeeting(conn, semesterTerm, ls[1], newLeId)) {
                    allFree = false;
                    break;
                }
            }

            if (allFree) {
                for (int[] ls : labSlots) {
                    try (PreparedStatement upd = conn.prepareStatement(
                            "UPDATE timetable_schedule SET lab_engineer_id = ? WHERE schedule_id = ?")) {
                        upd.setInt(1, newLeId);
                        upd.setInt(2, ls[0]);
                        upd.executeUpdate();
                    }
                }
                conn.commit();
                return "SUCCESS! Lab Engineer replaced directly across all 3 lab slots for " + courseTitle
                        + " with ZERO clashes.";
            } else {
                int oldRoomId = labSlots.get(0)[2];
                for (int[] ls : labSlots) {
                    try (PreparedStatement del = conn.prepareStatement(
                            "DELETE FROM timetable_schedule WHERE schedule_id = ?")) {
                        del.setInt(1, ls[0]);
                        del.executeUpdate();
                    }
                }
                boolean moved = relocateLabBlockInDB(conn, semesterTerm, allocationId, sectionId, instructorId, newLeId,
                        oldRoomId);
                conn.commit();
                if (moved) {
                    return "SUCCESS! New Lab Engineer had a clash in the old slot, so the 3-slot Lab block for "
                            + courseTitle + " was moved to a new conflict-free slot.";
                } else {
                    return "WARNING: Could not find 3 consecutive free slots for the new Lab Engineer.";
                }
            }

        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
            }
            return "Operation Failed: " + e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public String removeLabEngineer(String semesterTerm, int allocationId) {
        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);
            try (PreparedStatement ps1 = conn.prepareStatement(
                    "UPDATE course_allocations SET lab_engineer_id = NULL WHERE allocation_id = ?")) {
                ps1.setInt(1, allocationId);
                ps1.executeUpdate();
            }
            try (PreparedStatement ps2 = conn.prepareStatement(
                    "UPDATE timetable_schedule SET lab_engineer_id = NULL WHERE allocation_id = ? AND semester_term = ?")) {
                ps2.setInt(1, allocationId);
                ps2.setString(2, semesterTerm);
                ps2.executeUpdate();
            }
            conn.commit();
            return "SUCCESS! Lab Engineer has been removed from the selected course allocation and schedule.";
        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
            }
            return "Operation Failed: " + e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    // ========================================================================
    // 3. WEEKLY RESCHEDULE (DOES NOT TOUCH PERMANENT MASTER TIMETABLE!)
    // ========================================================================
    public String rescheduleClassForWeekOnly(String semesterTerm, int scheduleId) {
        Connection conn = null;
        try {
            conn = DatabaseConnection.getConnection();
            conn.setAutoCommit(false);

            int allocId = 0, sectionId = 0, instId = 0, origSlotId = 0;
            String oldDay = "", sessionType = "", courseTitle = "", secName = "";
            int oldPeriod = 0;

            String fetchSql = """
                        SELECT sch.allocation_id, sch.section_id, sch.instructor_id, sch.slot_id,
                               sch.session_type, ts.day_of_week, ts.period_no, c.course_title,
                               CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name) AS sec_lbl
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN batch_sections bs ON sch.section_id = bs.section_id
                        JOIN programs p ON bs.program_id = p.program_id
                        WHERE sch.schedule_id = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(fetchSql)) {
                ps.setInt(1, scheduleId);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    allocId = rs.getInt("allocation_id");
                    sectionId = rs.getInt("section_id");
                    instId = rs.getInt("instructor_id");
                    origSlotId = rs.getInt("slot_id");
                    sessionType = rs.getString("session_type");
                    oldDay = rs.getString("day_of_week");
                    oldPeriod = rs.getInt("period_no");
                    courseTitle = rs.getString("course_title");
                    secName = rs.getString("sec_lbl");
                } else {
                    return "ERROR: Selected class record not found.";
                }
            }

            if ("LAB".equals(sessionType)) {
                return "NOTE: 3-hour Lab blocks cannot be single-slot rescheduled here. Select a Theory or STI class.";
            }

            try (PreparedStatement chkDup = conn.prepareStatement(
                    "SELECT COUNT(*) FROM weekly_rescheduled_schedule WHERE semester_term = ? AND section_id = ? AND original_slot_id = ?")) {
                chkDup.setString(1, semesterTerm);
                chkDup.setInt(2, sectionId);
                chkDup.setInt(3, origSlotId);
                ResultSet rs = chkDup.executeQuery();
                if (rs.next() && rs.getInt(1) > 0) {
                    return "WARNING: This class (" + courseTitle + " on " + oldDay + " P" + oldPeriod
                            + ") is ALREADY rescheduled in this week's timetable!";
                }
            }

            List<String> existingWeeklyMoves = new ArrayList<>();
            String listMovesSql = """
                        SELECT ts.day_of_week, ts.period_no, c.course_title, CONCAT(f.rank_title, ' ', f.full_name) AS tname
                        FROM weekly_rescheduled_schedule wr
                        JOIN time_slots ts ON wr.new_slot_id = ts.slot_id
                        JOIN course_allocations ca ON wr.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN faculty f ON wr.instructor_id = f.faculty_id
                        WHERE wr.section_id = ? AND wr.semester_term = ?
                    """;
            try (PreparedStatement ps = conn.prepareStatement(listMovesSql)) {
                ps.setInt(1, sectionId);
                ps.setString(2, semesterTerm);
                ResultSet rs = ps.executeQuery();
                while (rs.next()) {
                    existingWeeklyMoves.add(rs.getString("day_of_week") + " Period " + rs.getInt("period_no") +
                            " -> " + rs.getString("course_title") + " (" + rs.getString("tname") + ")");
                }
            }

            List<TimeSlot> validSlots = loadValidMorningSlots(conn);
            Collections.shuffle(validSlots);

            TimeSlot chosenSlot = null;
            Integer chosenRoomId = null;
            String chosenRoomName = "Office/STI";

            for (TimeSlot s : validSlots) {
                if (s.slotId == origSlotId)
                    continue;

                String secPermSql = """
                            SELECT COUNT(*) FROM timetable_schedule sch
                            WHERE sch.semester_term = ? AND sch.section_id = ? AND sch.slot_id = ?
                            AND sch.slot_id NOT IN (
                                SELECT original_slot_id FROM weekly_rescheduled_schedule
                                WHERE semester_term = ? AND section_id = ?
                            )
                        """;
                try (PreparedStatement ps = conn.prepareStatement(secPermSql)) {
                    ps.setString(1, semesterTerm);
                    ps.setInt(2, sectionId);
                    ps.setInt(3, s.slotId);
                    ps.setString(4, semesterTerm);
                    ps.setInt(5, sectionId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0)
                        continue;
                }

                String secWeekSql = "SELECT COUNT(*) FROM weekly_rescheduled_schedule WHERE semester_term = ? AND section_id = ? AND new_slot_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(secWeekSql)) {
                    ps.setString(1, semesterTerm);
                    ps.setInt(2, sectionId);
                    ps.setInt(3, s.slotId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0)
                        continue;
                }

                if (!isInstructorFreeForTheoryOrMeeting(conn, semesterTerm, s.slotId, instId))
                    continue;

                String instWeekSql = "SELECT COUNT(*) FROM weekly_rescheduled_schedule WHERE semester_term = ? AND instructor_id = ? AND new_slot_id = ?";
                try (PreparedStatement ps = conn.prepareStatement(instWeekSql)) {
                    ps.setString(1, semesterTerm);
                    ps.setInt(2, instId);
                    ps.setInt(3, s.slotId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next() && rs.getInt(1) > 0)
                        continue;
                }

                if (!"STI".equals(sessionType)) {
                    String roomSql = """
                                SELECT r.room_id, r.room_name FROM rooms r
                                WHERE r.room_type IN ('CLASSROOM', 'LECTURE_HALL')
                                AND r.room_id NOT IN (
                                    SELECT room_id FROM timetable_schedule
                                    WHERE semester_term = ? AND slot_id = ? AND room_id IS NOT NULL
                                )
                                AND r.room_id NOT IN (
                                    SELECT room_id FROM weekly_rescheduled_schedule
                                    WHERE semester_term = ? AND new_slot_id = ? AND room_id IS NOT NULL
                                ) LIMIT 1
                            """;
                    try (PreparedStatement ps = conn.prepareStatement(roomSql)) {
                        ps.setString(1, semesterTerm);
                        ps.setInt(2, s.slotId);
                        ps.setString(3, semesterTerm);
                        ps.setInt(4, s.slotId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next()) {
                            chosenRoomId = rs.getInt("room_id");
                            chosenRoomName = rs.getString("room_name");
                        } else {
                            continue;
                        }
                    }
                }

                chosenSlot = s;
                break;
            }

            if (chosenSlot == null) {
                conn.rollback();
                return "FAILED: No mutual free slot found for " + secName
                        + " and the instructor without overlapping another class.";
            }

            String insSql = """
                        INSERT INTO weekly_rescheduled_schedule
                        (semester_term, section_id, allocation_id, original_slot_id, new_slot_id, instructor_id, room_id, session_type)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            try (PreparedStatement ps = conn.prepareStatement(insSql)) {
                ps.setString(1, semesterTerm);
                ps.setInt(2, sectionId);
                ps.setInt(3, allocId);
                ps.setInt(4, origSlotId);
                ps.setInt(5, chosenSlot.slotId);
                ps.setInt(6, instId);
                if (chosenRoomId != null)
                    ps.setInt(7, chosenRoomId);
                else
                    ps.setNull(7, Types.INTEGER);
                ps.setString(8, sessionType);
                ps.executeUpdate();
            }

            conn.commit();

            StringBuilder sb = new StringBuilder();
            sb.append("WEEKLY RESCHEDULE CREATED (Permanent Master Timetable Untouched!):\n");
            sb.append("• Section: ").append(secName).append(" | Course: ").append(courseTitle).append(" (")
                    .append(sessionType).append(")\n");
            sb.append("• Vacated Slot: ").append(oldDay).append(" Period ").append(oldPeriod).append("\n");
            sb.append("• New Weekly Slot: ").append(chosenSlot.dayOfWeek).append(" Period ").append(chosenSlot.periodNo)
                    .append(" (").append(chosenSlot.startTime).append("-").append(chosenSlot.endTime).append(") in ")
                    .append(chosenRoomName).append("\n");
            sb.append("• Verified: Zero overlap with other teachers or existing rescheduled classes.");

            if (!existingWeeklyMoves.isEmpty()) {
                sb.append("\n\nOther Active Weekly Reschedules in ").append(secName).append(":\n");
                for (String m : existingWeeklyMoves)
                    sb.append("   - ").append(m).append("\n");
            }
            return sb.toString();

        } catch (Exception e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException ignored) {
                }
            }
            return "Reschedule Error: " + e.getMessage();
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
    }

    public String clearWeeklyReschedulesForSection(String semesterTerm, int sectionId) {
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM weekly_rescheduled_schedule WHERE semester_term = ? AND section_id = ?")) {
            ps.setString(1, semesterTerm);
            ps.setInt(2, sectionId);
            ps.executeUpdate();
            return "SUCCESS";
        } catch (Exception e) {
            return "Error clearing weekly reschedules: " + e.getMessage();
        }
    }

    private boolean isInstructorFreeForTheoryOrMeeting(Connection conn, String term, int slotId, int personId)
            throws SQLException {
        String sql = """
                    SELECT COUNT(*) FROM timetable_schedule
                    WHERE semester_term = ? AND slot_id = ?
                    AND (
                        (instructor_id = ? AND session_type IN ('THEORY', 'STI'))
                        OR (lab_engineer_id = ? AND session_type = 'LAB')
                    )
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            ps.setInt(2, slotId);
            ps.setInt(3, personId);
            ps.setInt(4, personId);
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getInt(1) == 0;
        }
    }

    private String relocateMasterSession(Connection conn, String term, int allocId, int sectionId,
            int instId, String type) throws SQLException {
        List<TimeSlot> validSlots = loadValidMorningSlots(conn);
        Collections.shuffle(validSlots);

        for (TimeSlot s : validSlots) {
            String checkSec = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND slot_id = ? AND section_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(checkSec)) {
                ps.setString(1, term);
                ps.setInt(2, s.slotId);
                ps.setInt(3, sectionId);
                ResultSet rs = ps.executeQuery();
                if (rs.next() && rs.getInt(1) > 0)
                    continue;
            }
            if (!isInstructorFreeForTheoryOrMeeting(conn, term, s.slotId, instId))
                continue;

            Integer chosenRoom = null;
            if (!"STI".equals(type)) {
                String roomQuery = """
                            SELECT r.room_id FROM rooms r
                            WHERE r.room_type IN ('CLASSROOM', 'LECTURE_HALL')
                            AND r.room_id NOT IN (
                                SELECT room_id FROM timetable_schedule
                                WHERE semester_term = ? AND slot_id = ? AND room_id IS NOT NULL
                            ) LIMIT 1
                        """;
                try (PreparedStatement ps = conn.prepareStatement(roomQuery)) {
                    ps.setString(1, term);
                    ps.setInt(2, s.slotId);
                    ResultSet rs = ps.executeQuery();
                    if (rs.next())
                        chosenRoom = rs.getInt("room_id");
                    else
                        continue;
                }
            }

            String ins = "INSERT INTO timetable_schedule " +
                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type) "
                    +
                    "VALUES (?, ?, ?, ?, ?, NULL, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(ins)) {
                ps.setString(1, term);
                ps.setInt(2, allocId);
                ps.setInt(3, s.slotId);
                ps.setInt(4, sectionId);
                ps.setInt(5, instId);
                if (chosenRoom != null)
                    ps.setInt(6, chosenRoom);
                else
                    ps.setNull(6, Types.INTEGER);
                ps.setString(7, type);
                ps.executeUpdate();
                return s.dayOfWeek + " P" + s.periodNo;
            }
        }
        return null;
    }

    private boolean relocateLabBlockInDB(Connection conn, String term, int allocId, int sectionId,
            int instId, int leId, int oldLabRoomId) throws SQLException {
        Map<String, List<TimeSlot>> slotsByDay = groupSlotsByDay(loadValidMorningSlots(conn));
        List<Integer> labs = loadGeneralSoftwareLabs(conn);
        if (!labs.contains(oldLabRoomId) && oldLabRoomId > 0)
            labs.add(0, oldLabRoomId);

        for (List<TimeSlot> daySlots : slotsByDay.values()) {
            for (int i = 0; i <= daySlots.size() - 3; i++) {
                TimeSlot s1 = daySlots.get(i), s2 = daySlots.get(i + 1), s3 = daySlots.get(i + 2);
                if (s2.periodNo != s1.periodNo + 1 || s3.periodNo != s2.periodNo + 1)
                    continue;

                boolean peopleOk = true;
                for (TimeSlot s : new TimeSlot[] { s1, s2, s3 }) {
                    String q = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND slot_id = ? AND section_id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(q)) {
                        ps.setString(1, term);
                        ps.setInt(2, s.slotId);
                        ps.setInt(3, sectionId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next() && rs.getInt(1) > 0) {
                            peopleOk = false;
                            break;
                        }
                    }
                    if (leId > 0 && !isInstructorFreeForTheoryOrMeeting(conn, term, s.slotId, leId)) {
                        peopleOk = false;
                        break;
                    }
                }
                if (!peopleOk)
                    continue;

                for (int labId : labs) {
                    String rq = "SELECT COUNT(*) FROM timetable_schedule WHERE semester_term = ? AND room_id = ? AND slot_id IN (?, ?, ?)";
                    try (PreparedStatement ps = conn.prepareStatement(rq)) {
                        ps.setString(1, term);
                        ps.setInt(2, labId);
                        ps.setInt(3, s1.slotId);
                        ps.setInt(4, s2.slotId);
                        ps.setInt(5, s3.slotId);
                        ResultSet rs = ps.executeQuery();
                        if (rs.next() && rs.getInt(1) == 0) {
                            String ins = "INSERT INTO timetable_schedule " +
                                    "(semester_term, allocation_id, slot_id, section_id, instructor_id, lab_engineer_id, room_id, session_type, lab_group_tag) "
                                    +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, 'LAB', ?)";
                            for (TimeSlot s : new TimeSlot[] { s1, s2, s3 }) {
                                try (PreparedStatement ips = conn.prepareStatement(ins)) {
                                    ips.setString(1, term);
                                    ips.setInt(2, allocId);
                                    ips.setInt(3, s.slotId);
                                    ips.setInt(4, sectionId);
                                    ips.setInt(5, instId);
                                    if (leId > 0)
                                        ips.setInt(6, leId);
                                    else
                                        ips.setNull(6, Types.INTEGER);
                                    ips.setInt(7, labId);
                                    ips.setString(8, "LAB_" + allocId);
                                    ips.executeUpdate();
                                }
                            }
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean scheduleLabBlock(PreparedStatement stmt, String term, AllocationTask task,
            Map<String, List<TimeSlot>> slotsByDay, List<Integer> candidateLabs) throws SQLException {
        List<String> days = new ArrayList<>(slotsByDay.keySet());
        Collections.shuffle(days);

        for (String day : days) {
            List<TimeSlot> daySlots = slotsByDay.get(day);
            for (int i = 0; i <= daySlots.size() - 3; i++) {
                TimeSlot s1 = daySlots.get(i), s2 = daySlots.get(i + 1), s3 = daySlots.get(i + 2);
                if (s2.periodNo != s1.periodNo + 1 || s3.periodNo != s2.periodNo + 1)
                    continue;
                if (!isLabPeopleFree(s1.slotId, task) || !isLabPeopleFree(s2.slotId, task)
                        || !isLabPeopleFree(s3.slotId, task))
                    continue;

                for (int labId : candidateLabs) {
                    if (isRoomFree(s1.slotId, labId) && isRoomFree(s2.slotId, labId) && isRoomFree(s3.slotId, labId)) {
                        String tag = "LAB_" + task.allocationId;
                        for (TimeSlot s : new TimeSlot[] { s1, s2, s3 }) {
                            addBatchRow(stmt, term, task, s.slotId, labId, "LAB", tag);
                            markLabBusy(s.slotId, task, labId);
                        }
                        sectionCourseDays.add(task.sectionId + "_" + task.courseId + "_" + day);
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean scheduleSingleTheory(PreparedStatement stmt, String term, AllocationTask task,
            List<TimeSlot> validSlots, List<Integer> rooms, boolean allowSameDay) throws SQLException {
        List<TimeSlot> shuffledSlots = new ArrayList<>(validSlots);
        Collections.shuffle(shuffledSlots);

        for (TimeSlot s : shuffledSlots) {
            String dayKey = task.sectionId + "_" + task.courseId + "_" + s.dayOfWeek;
            if (!allowSameDay && sectionCourseDays.contains(dayKey))
                continue;
            if (!isInstructorAndSectionFree(s.slotId, task))
                continue;

            List<Integer> shuffledRooms = new ArrayList<>(rooms);
            Collections.shuffle(shuffledRooms);

            for (int roomId : shuffledRooms) {
                if (isRoomFree(s.slotId, roomId)) {
                    addBatchRow(stmt, term, task, s.slotId, roomId, "THEORY", null);
                    markTheoryBusy(s.slotId, task, roomId);
                    sectionCourseDays.add(dayKey);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean scheduleSTI(PreparedStatement stmt, String term, AllocationTask task,
            List<TimeSlot> validSlots) throws SQLException {
        List<TimeSlot> shuffledSlots = new ArrayList<>(validSlots);
        Collections.shuffle(shuffledSlots);

        for (TimeSlot s : shuffledSlots) {
            if (isInstructorAndSectionFree(s.slotId, task)) {
                addBatchRow(stmt, term, task, s.slotId, null, "STI", null);
                busySectionSlots.add(s.slotId + "_" + task.sectionId);
                busyInstructorSlots.add(s.slotId + "_" + task.instructorId);
                return true;
            }
        }
        return false;
    }

    private boolean isLabPeopleFree(int slotId, AllocationTask t) {
        if (busySectionSlots.contains(slotId + "_" + t.sectionId))
            return false;
        return t.labEngineerId == null || !busyLESlots.contains(slotId + "_" + t.labEngineerId);
    }

    private boolean isInstructorAndSectionFree(int slotId, AllocationTask t) {
        return !busySectionSlots.contains(slotId + "_" + t.sectionId)
                && !busyInstructorSlots.contains(slotId + "_" + t.instructorId);
    }

    private boolean isRoomFree(int slotId, int roomId) {
        return !busyRoomSlots.contains(slotId + "_" + roomId);
    }

    private void markLabBusy(int slotId, AllocationTask t, int roomId) {
        busySectionSlots.add(slotId + "_" + t.sectionId);
        if (t.labEngineerId != null)
            busyLESlots.add(slotId + "_" + t.labEngineerId);
        busyRoomSlots.add(slotId + "_" + roomId);
    }

    private void markTheoryBusy(int slotId, AllocationTask t, int roomId) {
        busySectionSlots.add(slotId + "_" + t.sectionId);
        busyInstructorSlots.add(slotId + "_" + t.instructorId);
        busyRoomSlots.add(slotId + "_" + roomId);
    }

    private void addBatchRow(PreparedStatement stmt, String term, AllocationTask t,
            int slotId, Integer roomId, String type, String tag) throws SQLException {
        stmt.setString(1, term);
        stmt.setInt(2, t.allocationId);
        stmt.setInt(3, slotId);
        stmt.setInt(4, t.sectionId);
        stmt.setInt(5, t.instructorId);
        if (t.labEngineerId != null && "LAB".equals(type))
            stmt.setInt(6, t.labEngineerId);
        else
            stmt.setNull(6, Types.INTEGER);
        if (roomId != null)
            stmt.setInt(7, roomId);
        else
            stmt.setNull(7, Types.INTEGER);
        stmt.setString(8, type);
        if (tag != null)
            stmt.setString(9, tag);
        else
            stmt.setNull(9, Types.VARCHAR);
        stmt.addBatch();
    }

    private List<TimeSlot> loadValidMorningSlots(Connection conn) throws SQLException {
        List<TimeSlot> list = new ArrayList<>();
        String sql = "SELECT slot_id, day_of_week, period_no, start_time, end_time FROM time_slots " +
                "WHERE shift_type = 'MORNING' AND is_global_blocked = FALSE ORDER BY FIELD(day_of_week,'MON','TUE','WED','THU','FRI'), period_no";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                TimeSlot ts = new TimeSlot();
                ts.slotId = rs.getInt("slot_id");
                ts.dayOfWeek = rs.getString("day_of_week");
                ts.periodNo = rs.getInt("period_no");
                ts.startTime = rs.getString("start_time");
                ts.endTime = rs.getString("end_time");
                list.add(ts);
            }
        }
        return list;
    }

    private Map<String, List<TimeSlot>> groupSlotsByDay(List<TimeSlot> slots) {
        Map<String, List<TimeSlot>> map = new LinkedHashMap<>();
        for (TimeSlot s : slots) {
            map.computeIfAbsent(s.dayOfWeek, k -> new ArrayList<>()).add(s);
        }
        return map;
    }

    private List<Integer> loadRoomsByType(Connection conn, String type, String specialty) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = (specialty == null)
                ? "SELECT room_id FROM rooms WHERE room_type = ?"
                : "SELECT room_id FROM rooms WHERE room_type = ? AND lab_specialty = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, type);
            if (specialty != null)
                ps.setString(2, specialty);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next())
                    ids.add(rs.getInt("room_id"));
            }
        }
        return ids;
    }

    private List<Integer> loadGeneralSoftwareLabs(Connection conn) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT room_id FROM rooms WHERE room_type = 'LAB' AND (lab_specialty IS NULL OR lab_specialty <> 'PHYSICS')";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next())
                ids.add(rs.getInt("room_id"));
        }
        return ids;
    }

    private List<AllocationTask> loadAllocations(Connection conn, String term) throws SQLException {
        List<AllocationTask> list = new ArrayList<>();
        String sql = """
                    SELECT ca.*, c.course_title, c.required_lab_specialty,
                           CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name) AS sec_label
                    FROM course_allocations ca
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN batch_sections bs ON ca.section_id = bs.section_id
                    JOIN programs p ON bs.program_id = p.program_id
                    WHERE ca.semester_term = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, term);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AllocationTask t = new AllocationTask();
                    t.allocationId = rs.getInt("allocation_id");
                    t.sectionId = rs.getInt("section_id");
                    t.sectionLabel = rs.getString("sec_label");
                    t.courseId = rs.getInt("course_id");
                    t.courseTitle = rs.getString("course_title");
                    t.instructorId = rs.getInt("instructor_id");
                    int le = rs.getInt("lab_engineer_id");
                    t.labEngineerId = rs.wasNull() ? null : le;
                    t.theorySlotsNeeded = rs.getInt("theory_slots_per_week");
                    t.labBlocksNeeded = rs.getInt("lab_blocks_per_week");
                    t.stiSlotsNeeded = rs.getInt("sti_slots_per_week");
                    t.requiredLabSpecialty = rs.getString("required_lab_specialty");
                    list.add(t);
                }
            }
        }
        return list;
    }
}