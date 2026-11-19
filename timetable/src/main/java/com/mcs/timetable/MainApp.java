package com.mcs.timetable;

import com.mcs.timetable.db.DatabaseConnection;
import com.mcs.timetable.engine.TimetableGenerator;

import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.stage.FileChooser;
import java.io.File;
import java.io.PrintWriter;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;

public class MainApp extends Application {

    // Tab 1 Controls
    private final ComboBox<String> sectionDropdown = new ComboBox<>();
    private final Map<String, Integer> sectionMap = new HashMap<>();
    private final GridPane timetableGrid = new GridPane();
    private final TextArea statusArea = new TextArea(
            "Ready. Click 'Generate Conflict-Free Timetable' to schedule Fall-2026.");

    // Tab 2 Tables
    private final TableView<ObservableList<String>> facultyTable = new TableView<>();
    private final TableView<ObservableList<String>> roomTable = new TableView<>();

    // Tab 3 Allocation Controls
    private final ComboBox<String> allocSectionBox = new ComboBox<>();
    private final ComboBox<String> allocCourseBox = new ComboBox<>();
    private final ComboBox<String> allocInstBox = new ComboBox<>();
    private final ComboBox<String> allocLEBox = new ComboBox<>();
    private final Map<String, Integer> courseMap = new HashMap<>();
    private final Map<String, Integer> facultyMap = new HashMap<>();
    private final Map<String, Integer> leMap = new HashMap<>();
    private final TableView<ObservableList<String>> allocTable = new TableView<>();

    // Tab 4 Lookup Controls
    private final ComboBox<String> lookupTypeBox = new ComboBox<>();
    private final ComboBox<String> lookupEntityBox = new ComboBox<>();
    private final TableView<ObservableList<String>> lookupTable = new TableView<>();

    @Override
    public void start(Stage stage) {
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab tab1 = new Tab("📅 Timetable & Generator", buildTimetableTab());
        Tab tab2 = new Tab("👨‍🏫 Faculty & Rooms", buildMasterDataTab());
        Tab tab3 = new Tab("📚 Course Allocations", buildAllocationsTab());
        Tab tab4 = new Tab("🔍 Teacher / Room Schedule", buildLookupTab());

        tabPane.getTabs().addAll(tab1, tab2, tab3, tab4);

        // Refresh data when switching tabs
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldT, newT) -> {
            refreshAllDropdownsAndTables();
        });

        refreshAllDropdownsAndTables();

        Scene scene = new Scene(tabPane, 1300, 750);
        stage.setTitle("MCS NUST - Automated Training Program & Conflict-Free Scheduler");
        stage.setScene(scene);
        stage.show();
    }

    // ========================================================================
    // TAB 1: TIMETABLE GRID & AUTO-GENERATOR
    // ========================================================================
    private VBox buildTimetableTab() {
        Label title = new Label("MCS NUST - Automated Training Program & Timetable System (Fall-2026)");
        title.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        Button generateBtn = new Button("⚡ Generate Conflict-Free Timetable");
        Button exportBtn = new Button("📄 Export CSV");
        exportBtn.setStyle(
                "-fx-background-color: #0277bd; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16;");

        exportBtn.setOnAction(e -> {
            String secName = sectionDropdown.getValue();
            if (secName == null)
                return;
            FileChooser fc = new FileChooser();
            fc.setInitialFileName(secName.replace(" ", "_") + "_Timetable_Fall2026.csv");
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
            File file = fc.showSaveDialog(null);
            if (file != null) {
                exportSectionToCSV(sectionMap.get(secName), secName, file);
            }
        });
        generateBtn.setStyle(
                "-fx-background-color: #1b5e20; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8 16;");

        sectionDropdown.setPrefWidth(220);

        HBox controls = new HBox(15, new Label("Select Section:"), sectionDropdown, generateBtn, exportBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        timetableGrid.setGridLinesVisible(true);
        timetableGrid.setHgap(2);
        timetableGrid.setVgap(2);
        timetableGrid.setStyle("-fx-background-color: #ffffff; -fx-padding: 8;");

        ScrollPane scrollPane = new ScrollPane(timetableGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(500);

        statusArea.setEditable(false);
        statusArea.setPrefRowCount(3);
        statusArea.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 12px;");

        generateBtn.setOnAction(e -> {
            TimetableGenerator generator = new TimetableGenerator();
            String result = generator.generateForTerm("Fall-2026");
            statusArea.setText(result);
            if (sectionDropdown.getValue() != null && sectionMap.containsKey(sectionDropdown.getValue())) {
                renderTimetableForSection(sectionMap.get(sectionDropdown.getValue()));
            }
        });

        sectionDropdown.setOnAction(e -> {
            if (sectionDropdown.getValue() != null && sectionMap.containsKey(sectionDropdown.getValue())) {
                renderTimetableForSection(sectionMap.get(sectionDropdown.getValue()));
            }
        });

        VBox box = new VBox(10, title, controls, scrollPane, new Label("Generator Log / Status:"), statusArea);
        box.setPadding(new Insets(15));
        box.setStyle("-fx-background-color: #f4f6f9;");
        return box;
    }

    // ========================================================================
    // TAB 2: MANAGE FACULTY & ROOMS (CRUD)
    // ========================================================================
    private HBox buildMasterDataTab() {
        // Left Side: Faculty Management
        Label fTitle = new Label("Add Faculty / Lab Engineer");
        fTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        ComboBox<String> rankBox = new ComboBox<>(
                FXCollections.observableArrayList("Dr", "Lt Col", "Maj", "Lec", "Snr Lec", "TVF", "LE"));
        rankBox.setValue("Lec");
        TextField nameField = new TextField();
        nameField.setPromptText("Full Name (e.g., Ayesha Naseer)");
        ComboBox<String> roleBox = new ComboBox<>(
                FXCollections.observableArrayList("INSTRUCTOR", "LAB_ENGINEER", "BOTH"));
        roleBox.setValue("INSTRUCTOR");
        TextField deptField = new TextField("CSE");
        Button addFacultyBtn = new Button("+ Add Faculty");
        addFacultyBtn.setStyle("-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold;");

        addFacultyBtn.setOnAction(e -> {
            if (nameField.getText().isBlank())
                return;
            String sql = "INSERT INTO faculty (rank_title, full_name, designation_role, department) VALUES (?, ?, ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, rankBox.getValue());
                ps.setString(2, nameField.getText().trim());
                ps.setString(3, roleBox.getValue());
                ps.setString(4, deptField.getText().trim());
                ps.executeUpdate();
                nameField.clear();
                loadFacultyTable();
                showAlert("Success", "Faculty member added to database!");
            } catch (SQLException ex) {
                showAlert("Database Error", ex.getMessage());
            }
        });

        HBox fForm = new HBox(8, rankBox, nameField, roleBox, deptField, addFacultyBtn);
        setupTableColumns(facultyTable, new String[] { "ID", "Rank", "Full Name", "Role", "Dept" });
        VBox leftPane = new VBox(10, fTitle, fForm, facultyTable);
        leftPane.setPadding(new Insets(15));
        HBox.setHgrow(leftPane, Priority.ALWAYS);

        // Right Side: Room & Lab Management
        Label rTitle = new Label("Add Classroom / Lab");
        rTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        TextField roomNameField = new TextField();
        roomNameField.setPromptText("Room Name (e.g., CR-37)");
        ComboBox<String> roomTypeBox = new ComboBox<>(
                FXCollections.observableArrayList("CLASSROOM", "LECTURE_HALL", "LAB"));
        roomTypeBox.setValue("CLASSROOM");
        ComboBox<String> specBox = new ComboBox<>(FXCollections.observableArrayList("NONE", "PROGRAMMING", "DATABASE",
                "NETWORKING", "OPERATING_SYSTEM", "SOFTWARE_CONST", "PHYSICS"));
        specBox.setValue("NONE");
        TextField capField = new TextField("55");
        capField.setPrefWidth(60);
        Button addRoomBtn = new Button("+ Add Room");
        addRoomBtn.setStyle("-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold;");

        addRoomBtn.setOnAction(e -> {
            if (roomNameField.getText().isBlank())
                return;
            String sql = "INSERT INTO rooms (room_name, room_type, lab_specialty, capacity) VALUES (?, ?, ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, roomNameField.getText().trim());
                ps.setString(2, roomTypeBox.getValue());
                if ("NONE".equals(specBox.getValue()))
                    ps.setNull(3, Types.VARCHAR);
                else
                    ps.setString(3, specBox.getValue());
                ps.setInt(4, Integer.parseInt(capField.getText().trim()));
                ps.executeUpdate();
                roomNameField.clear();
                loadRoomTable();
                showAlert("Success", "Room/Lab added to database!");
            } catch (Exception ex) {
                showAlert("Error", ex.getMessage());
            }
        });

        HBox rForm = new HBox(8, roomNameField, roomTypeBox, specBox, capField, addRoomBtn);
        setupTableColumns(roomTable, new String[] { "ID", "Room Name", "Type", "Lab Specialty", "Capacity" });
        VBox rightPane = new VBox(10, rTitle, rForm, roomTable);
        rightPane.setPadding(new Insets(15));
        HBox.setHgrow(rightPane, Priority.ALWAYS);

        return new HBox(10, leftPane, rightPane);
    }

    // ========================================================================
    // TAB 3: COURSE ALLOCATIONS (ASSIGN COURSES TO SECTIONS)
    // ========================================================================
    private VBox buildAllocationsTab() {
        Label title = new Label("Assign Courses to Sections (Fall-2026 Load Allocation)");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        allocSectionBox.setPromptText("Section");
        allocCourseBox.setPromptText("Course");
        allocInstBox.setPromptText("Instructor");
        allocLEBox.setPromptText("Lab Engineer (Optional)");

        Spinner<Integer> theorySpin = new Spinner<>(0, 5, 3);
        theorySpin.setPrefWidth(65);
        Spinner<Integer> labSpin = new Spinner<>(0, 2, 0);
        labSpin.setPrefWidth(65);
        Spinner<Integer> stiSpin = new Spinner<>(0, 2, 1);
        stiSpin.setPrefWidth(65);

        Button assignBtn = new Button("+ Allocate Course");
        assignBtn.setStyle("-fx-background-color: #1b5e20; -fx-text-fill: white; -fx-font-weight: bold;");

        Button deleteAllocBtn = new Button("🗑 Remove Selected");
        deleteAllocBtn.setStyle("-fx-background-color: #b71c1c; -fx-text-fill: white;");

        assignBtn.setOnAction(e -> {
            if (allocSectionBox.getValue() == null || allocCourseBox.getValue() == null
                    || allocInstBox.getValue() == null) {
                showAlert("Validation", "Please select Section, Course, and Instructor.");
                return;
            }
            String sql = "INSERT INTO course_allocations (semester_term, section_id, course_id, instructor_id, lab_engineer_id, theory_slots_per_week, lab_blocks_per_week, sti_slots_per_week) "
                    +
                    "VALUES ('Fall-2026', ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, sectionMap.get(allocSectionBox.getValue()));
                ps.setInt(2, courseMap.get(allocCourseBox.getValue()));
                ps.setInt(3, facultyMap.get(allocInstBox.getValue()));
                if (allocLEBox.getValue() == null || "NONE".equals(allocLEBox.getValue()))
                    ps.setNull(4, Types.INTEGER);
                else
                    ps.setInt(4, leMap.get(allocLEBox.getValue()));
                ps.setInt(5, theorySpin.getValue());
                ps.setInt(6, labSpin.getValue());
                ps.setInt(7, stiSpin.getValue());
                ps.executeUpdate();
                loadAllocationsTable();
                showAlert("Success", "Course allocated! Go to Tab 1 and click Generate to update timetable.");
            } catch (SQLException ex) {
                showAlert("Database Error", ex.getMessage());
            }
        });

        deleteAllocBtn.setOnAction(e -> {
            ObservableList<String> selected = allocTable.getSelectionModel().getSelectedItem();
            if (selected == null)
                return;
            try (Connection conn = DatabaseConnection.getConnection();
                    PreparedStatement ps = conn
                            .prepareStatement("DELETE FROM course_allocations WHERE allocation_id = ?")) {
                ps.setInt(1, Integer.parseInt(selected.get(0)));
                ps.executeUpdate();
                loadAllocationsTable();
            } catch (SQLException ex) {
                showAlert("Error", ex.getMessage());
            }
        });

        HBox form = new HBox(10,
                allocSectionBox, allocCourseBox, allocInstBox, allocLEBox,
                new Label("Theory:"), theorySpin,
                new Label("Lab Blk:"), labSpin,
                new Label("STI:"), stiSpin,
                assignBtn, deleteAllocBtn);
        form.setAlignment(Pos.CENTER_LEFT);

        setupTableColumns(allocTable, new String[] { "ID", "Section", "Course", "Instructor", "Lab Engineer",
                "Theory/Wk", "Lab Blk/Wk", "STI/Wk" });
        VBox box = new VBox(12, title, form, allocTable);
        box.setPadding(new Insets(15));
        return box;
    }

    // ========================================================================
    // TAB 4: TEACHER & ROOM SCHEDULE LOOKUP (CLASH VERIFICATION)
    // ========================================================================
    private VBox buildLookupTab() {
        Label title = new Label("Verify Zero-Clash Schedules by Instructor or Room/Lab");
        title.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #1a237e;");

        lookupTypeBox.setItems(FXCollections.observableArrayList("By Faculty / LE", "By Room / Lab"));
        lookupTypeBox.setValue("By Faculty / LE");
        lookupEntityBox.setPrefWidth(260);

        lookupTypeBox.setOnAction(e -> populateLookupEntities());
        lookupEntityBox.setOnAction(e -> runLookupQuery());

        HBox bar = new HBox(12, new Label("Filter Mode:"), lookupTypeBox, new Label("Select:"), lookupEntityBox);
        bar.setAlignment(Pos.CENTER_LEFT);

        setupTableColumns(lookupTable,
                new String[] { "Day", "Period", "Time", "Section", "Course", "Type", "Room / Faculty" });

        VBox box = new VBox(12, title, bar, lookupTable);
        box.setPadding(new Insets(15));
        return box;
    }

    // ========================================================================
    // DATABASE LOADERS & HELPERS
    // ========================================================================
    private void refreshAllDropdownsAndTables() {
        loadSections();
        loadCoursesAndFacultyMaps();
        loadFacultyTable();
        loadRoomTable();
        loadAllocationsTable();
        populateLookupEntities();
    }

    private void loadSections() {
        String prev = sectionDropdown.getValue();
        sectionMap.clear();
        sectionDropdown.getItems().clear();
        allocSectionBox.getItems().clear();

        String sql = "SELECT bs.section_id, CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name) AS label " +
                "FROM batch_sections bs JOIN programs p ON bs.program_id = p.program_id ORDER BY bs.batch_no, bs.section_name";
        try (Connection conn = DatabaseConnection.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String label = rs.getString("label");
                sectionMap.put(label, rs.getInt("section_id"));
                sectionDropdown.getItems().add(label);
                allocSectionBox.getItems().add(label);
            }
            if (prev != null && sectionMap.containsKey(prev))
                sectionDropdown.setValue(prev);
            else if (!sectionDropdown.getItems().isEmpty())
                sectionDropdown.getSelectionModel().selectFirst();
        } catch (SQLException e) {
            statusArea.setText("DB Error: " + e.getMessage());
        }
    }

    private void loadCoursesAndFacultyMaps() {
        courseMap.clear();
        facultyMap.clear();
        leMap.clear();
        allocCourseBox.getItems().clear();
        allocInstBox.getItems().clear();
        allocLEBox.getItems().clear();
        allocLEBox.getItems().add("NONE");

        try (Connection conn = DatabaseConnection.getConnection(); Statement st = conn.createStatement()) {
            ResultSet rsC = st.executeQuery(
                    "SELECT course_id, CONCAT(course_code, ' - ', course_title) AS lbl FROM courses ORDER BY course_code");
            while (rsC.next()) {
                courseMap.put(rsC.getString("lbl"), rsC.getInt("course_id"));
                allocCourseBox.getItems().add(rsC.getString("lbl"));
            }
            ResultSet rsF = st.executeQuery(
                    "SELECT faculty_id, CONCAT(rank_title, ' ', full_name) AS lbl, designation_role FROM faculty ORDER BY full_name");
            while (rsF.next()) {
                String lbl = rsF.getString("lbl");
                int id = rsF.getInt("faculty_id");
                String role = rsF.getString("designation_role");
                if (!"LAB_ENGINEER".equals(role)) {
                    facultyMap.put(lbl, id);
                    allocInstBox.getItems().add(lbl);
                }
                if (!"INSTRUCTOR".equals(role)) {
                    leMap.put(lbl, id);
                    allocLEBox.getItems().add(lbl);
                }
            }
        } catch (SQLException ignored) {
        }
    }

    private void loadFacultyTable() {
        fillTable(facultyTable,
                "SELECT faculty_id, rank_title, full_name, designation_role, department FROM faculty ORDER BY faculty_id DESC");
    }

    private void loadRoomTable() {
        fillTable(roomTable,
                "SELECT room_id, room_name, room_type, IFNULL(lab_specialty, '-'), capacity FROM rooms ORDER BY room_type, room_name");
    }

    private void loadAllocationsTable() {
        String sql = """
                    SELECT ca.allocation_id,
                           CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name),
                           c.course_title,
                           CONCAT(f.rank_title, ' ', f.full_name),
                           IFNULL(CONCAT(le.rank_title, ' ', le.full_name), '-'),
                           ca.theory_slots_per_week, ca.lab_blocks_per_week, ca.sti_slots_per_week
                    FROM course_allocations ca
                    JOIN batch_sections bs ON ca.section_id = bs.section_id
                    JOIN programs p ON bs.program_id = p.program_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON ca.instructor_id = f.faculty_id
                    LEFT JOIN faculty le ON ca.lab_engineer_id = le.faculty_id
                    ORDER BY bs.batch_no, bs.section_name, c.course_code
                """;
        fillTable(allocTable, sql);
    }

    private void populateLookupEntities() {
        lookupEntityBox.getItems().clear();
        String mode = lookupTypeBox.getValue();
        String sql = "By Faculty / LE".equals(mode)
                ? "SELECT CONCAT(rank_title, ' ', full_name) AS name FROM faculty ORDER BY full_name"
                : "SELECT room_name AS name FROM rooms ORDER BY room_name";
        try (Connection conn = DatabaseConnection.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            while (rs.next())
                lookupEntityBox.getItems().add(rs.getString("name"));
            if (!lookupEntityBox.getItems().isEmpty())
                lookupEntityBox.getSelectionModel().selectFirst();
        } catch (SQLException ignored) {
        }
    }

    private void runLookupQuery() {
        String selected = lookupEntityBox.getValue();
        if (selected == null)
            return;
        String mode = lookupTypeBox.getValue();

        String sql = "By Faculty / LE".equals(mode)
                ? """
                        SELECT ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time),
                               CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name),
                               c.course_title, sch.session_type, IFNULL(r.room_name, 'Office/STI')
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN batch_sections bs ON sch.section_id = bs.section_id
                        JOIN programs p ON bs.program_id = p.program_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN faculty f ON sch.instructor_id = f.faculty_id
                        LEFT JOIN faculty le ON sch.lab_engineer_id = le.faculty_id
                        LEFT JOIN rooms r ON sch.room_id = r.room_id
                        WHERE CONCAT(f.rank_title, ' ', f.full_name) = ? OR CONCAT(le.rank_title, ' ', le.full_name) = ?
                        ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                        """
                : """
                        SELECT ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time),
                               CONCAT(p.program_code, '-', bs.batch_no, ' ', bs.section_name),
                               c.course_title, sch.session_type, CONCAT(f.rank_title, ' ', f.full_name)
                        FROM timetable_schedule sch
                        JOIN time_slots ts ON sch.slot_id = ts.slot_id
                        JOIN batch_sections bs ON sch.section_id = bs.section_id
                        JOIN programs p ON bs.program_id = p.program_id
                        JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                        JOIN courses c ON ca.course_id = c.course_id
                        JOIN faculty f ON sch.instructor_id = f.faculty_id
                        JOIN rooms r ON sch.room_id = r.room_id
                        WHERE r.room_name = ?
                        ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                        """;

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, selected);
            if ("By Faculty / LE".equals(mode))
                ps.setString(2, selected);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= 7; i++)
                    row.add(rs.getString(i));
                rows.add(row);
            }
            lookupTable.setItems(rows);
        } catch (SQLException ignored) {
        }
    }

    private void renderTimetableForSection(int sectionId) {
        timetableGrid.getChildren().clear();
        String[] days = { "MON", "TUE", "WED", "THU", "FRI" };
        String[] headers = { "Day / Period", "1st\n(0800-0850)", "2nd\n(0900-0950)", "3rd\n(1000-1050)",
                "4th\n(1100-1150)", "5th\n(1200-1250)", "6th\n(1310-1400)", "7th\n(1420-1510)" };

        for (int col = 0; col < headers.length; col++) {
            Label lbl = new Label(headers[col]);
            lbl.setMaxWidth(Double.MAX_VALUE);
            lbl.setAlignment(Pos.CENTER);
            lbl.setStyle("-fx-background-color: #1a237e; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 8;");
            timetableGrid.add(lbl, col, 0);
        }

        Map<String, Label> cellMap = new HashMap<>();
        for (int r = 0; r < days.length; r++) {
            String day = days[r];
            Label dayLbl = new Label(day);
            dayLbl.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
            dayLbl.setAlignment(Pos.CENTER);
            dayLbl.setStyle(
                    "-fx-background-color: #283593; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 10;");
            timetableGrid.add(dayLbl, 0, r + 1);

            for (int p = 1; p <= 7; p++) {
                Label cell = new Label("-");
                cell.setWrapText(true);
                cell.setPrefSize(160, 82);
                cell.setAlignment(Pos.CENTER);
                cell.setStyle("-fx-background-color: #ffffff; -fx-padding: 5; -fx-font-size: 11px;");

                if ("WED".equals(day) && (p == 3 || p == 4)) {
                    cell.setText("SEMINAR /\nCI (E) / HOD");
                    cell.setStyle(
                            "-fx-background-color: #ffe0b2; -fx-font-weight: bold; -fx-padding: 5; -fx-font-size: 11px;");
                } else if ("FRI".equals(day) && p == 6) {
                    cell.setText("FRIDAY\nPRAYERS");
                    cell.setStyle(
                            "-fx-background-color: #c8e6c9; -fx-font-weight: bold; -fx-padding: 5; -fx-font-size: 11px;");
                } else if ("FRI".equals(day) && p == 7) {
                    cell.setText("LIBRARY");
                    cell.setStyle(
                            "-fx-background-color: #e1f5fe; -fx-font-weight: bold; -fx-padding: 5; -fx-font-size: 11px;");
                }
                timetableGrid.add(cell, p, r + 1);
                cellMap.put(day + "_" + p, cell);
            }
        }

        String query = """
                    SELECT ts.day_of_week, ts.period_no, c.course_title,
                           CONCAT(f.rank_title, ' ', f.full_name) AS inst_name,
                           CONCAT(le.rank_title, ' ', le.full_name) AS le_name,
                           r.room_name, sch.session_type
                    FROM timetable_schedule sch
                    JOIN time_slots ts ON sch.slot_id = ts.slot_id
                    JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON sch.instructor_id = f.faculty_id
                    LEFT JOIN faculty le ON sch.lab_engineer_id = le.faculty_id
                    LEFT JOIN rooms r ON sch.room_id = r.room_id
                    WHERE sch.section_id = ? AND sch.semester_term = 'Fall-2026'
                """;

        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn.prepareStatement(query)) {
            ps.setInt(1, sectionId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Label cell = cellMap.get(rs.getString("day_of_week") + "_" + rs.getInt("period_no"));
                if (cell != null) {
                    String type = rs.getString("session_type");
                    String course = rs.getString("course_title");
                    String inst = rs.getString("inst_name");
                    String room = rs.getString("room_name");

                    if ("STI".equals(type)) {
                        cell.setText("STI\n" + inst);
                        cell.setStyle("-fx-background-color: #f3e5f5; -fx-padding: 5; -fx-font-size: 11px;");
                    } else if ("LAB".equals(type)) {
                        cell.setText(course + " (LAB)\n" + inst + " / " + rs.getString("le_name") + "\n(" + room + ")");
                        cell.setStyle(
                                "-fx-background-color: #bbdefb; -fx-font-weight: bold; -fx-padding: 5; -fx-font-size: 10px;");
                    } else {
                        cell.setText(course + "\n" + inst + "\n(" + room + ")");
                        cell.setStyle("-fx-background-color: #fff9c4; -fx-padding: 5; -fx-font-size: 11px;");
                    }
                }
            }
        } catch (SQLException e) {
            statusArea.setText("Error loading grid: " + e.getMessage());
        }
    }

    private void setupTableColumns(TableView<ObservableList<String>> table, String[] cols) {
        table.getColumns().clear();
        for (int i = 0; i < cols.length; i++) {
            final int colIdx = i;
            TableColumn<ObservableList<String>, String> col = new TableColumn<>(cols[i]);
            col.setCellValueFactory(param -> new SimpleStringProperty(param.getValue().get(colIdx)));
            col.setPrefWidth(140);
            table.getColumns().add(col);
        }
    }

    private void fillTable(TableView<ObservableList<String>> table, String sql) {
        ObservableList<ObservableList<String>> data = FXCollections.observableArrayList();
        try (Connection conn = DatabaseConnection.getConnection();
                Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            int colCount = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                ObservableList<String> row = FXCollections.observableArrayList();
                for (int i = 1; i <= colCount; i++)
                    row.add(rs.getString(i));
                data.add(row);
            }
            table.setItems(data);
        } catch (SQLException ignored) {
        }
    }

    private void showAlert(String title, String msg) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

    private void exportSectionToCSV(int sectionId, String sectionName, File file) {
        String sql = """
                    SELECT ts.day_of_week, ts.period_no, CONCAT(ts.start_time, '-', ts.end_time) AS timing,
                           c.course_code, c.course_title, sch.session_type,
                           CONCAT(f.rank_title, ' ', f.full_name) AS instructor,
                           IFNULL(CONCAT(le.rank_title, ' ', le.full_name), '-') AS lab_engineer,
                           IFNULL(r.room_name, 'N/A') AS room
                    FROM timetable_schedule sch
                    JOIN time_slots ts ON sch.slot_id = ts.slot_id
                    JOIN course_allocations ca ON sch.allocation_id = ca.allocation_id
                    JOIN courses c ON ca.course_id = c.course_id
                    JOIN faculty f ON sch.instructor_id = f.faculty_id
                    LEFT JOIN faculty le ON sch.lab_engineer_id = le.faculty_id
                    LEFT JOIN rooms r ON sch.room_id = r.room_id
                    WHERE sch.section_id = ? AND sch.semester_term = 'Fall-2026'
                    ORDER BY FIELD(ts.day_of_week,'MON','TUE','WED','THU','FRI'), ts.period_no
                """;
        try (Connection conn = DatabaseConnection.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql);
                PrintWriter pw = new PrintWriter(file)) {
            ps.setInt(1, sectionId);
            ResultSet rs = ps.executeQuery();
            pw.println("Section,Day,Period,Timing,Course Code,Course Title,Session Type,Instructor,Lab Engineer,Room");
            while (rs.next()) {
                pw.printf("\"%s\",\"%s\",%d,\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"%n",
                        sectionName, rs.getString(1), rs.getInt(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getString(7), rs.getString(8), rs.getString(9));
            }
            showAlert("Export Complete", "Timetable exported successfully to:\n" + file.getAbsolutePath());
        } catch (Exception ex) {
            showAlert("Export Error", ex.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
