module com.mcs {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.sql;
    requires javafx.graphics;

    opens com.mcs.timetable to javafx.fxml;

    exports com.mcs.timetable;
}
