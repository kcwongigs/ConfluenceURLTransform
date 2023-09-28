package com.igsl.export;

import java.io.FileWriter;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import com.igsl.config.Config;

public class JiraAttachment extends ObjectExport {

	private static final String SQL = "SELECT ID, ISSUEID, MIMETYPE, FILENAME, AUTHOR FROM FILEATTACHMENT";
	
	@Override
	public Path exportObjects(Config config) throws Exception {
		Connection conn = config.getConnections().getJiraConnection();
		Path p = getOutputPath(config);
		try (	FileWriter fw = new FileWriter(p.toFile(), false);
				CSVPrinter printer = new CSVPrinter(fw, CSVFormat.DEFAULT);
				PreparedStatement ps = conn.prepareStatement(SQL)) {
			printer.printRecord("ID", "ISSUEID", "MIMETYPE", "FILENAME", "AUTHOR");
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String id = rs.getString(1);
					String issueId = rs.getString(2);
					String mimeType = rs.getString(3);
					String fileName = rs.getString(4);
					String author = rs.getString(5);
					printer.printRecord(id, issueId, mimeType, fileName, author);
				}
			}
		}
		return p;
	}

}
