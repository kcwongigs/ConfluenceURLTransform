package com.igsl.export.dc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.csv.CSVRecord;

import com.igsl.config.SQLConfig;

public class JiraFilter extends ObjectExport {

	private static final String SQL = SQLConfig.getInstance().getSQL(JiraFilter.class);
//			"SELECT id, filtername, description, authorname, groupname, projectid, reqcontent FROM searchrequest";
	public static final String COL_ID = "ID";
	public static final String COL_FILTERNAME = "FILTERNAME";
	public static final String COL_DESCRIPTION = "DESCRIPTION";
	public static final String COL_CONTENT = "CONTENT";
	public static final List<String> COL_LIST = Arrays.asList(
			COL_ID, COL_FILTERNAME, COL_DESCRIPTION, COL_CONTENT);
	
	private PreparedStatement ps;
	private ResultSet rs;

	@Override
	public List<String> getHeaders() {
		return COL_LIST;
	}

	@Override
	public void startGetObjects() throws Exception {
		Connection conn = config.getConnections().getJiraConnection();
		ps = conn.prepareStatement(SQL);
		rs = ps.executeQuery();
	}

	@Override
	public List<String> getNextObject() throws Exception {
		if (rs.next()) {
			String id = rs.getString(1);
			String filterName = rs.getString(2);
			String description = rs.getString(3);
			String content = rs.getString(4);
			return Arrays.asList(id, filterName, description, content);
		}
		return null;
	}

	@Override
	public void stopGetObjects() throws Exception {
		close(rs);
		close(ps);
	}

	@Override
	protected String getObjectKey(CSVRecord r) throws Exception {
		String filterName = r.get(COL_FILTERNAME);
		return filterName;
	}

	@Override
	protected String getObjectId(CSVRecord r) throws Exception {
		String id = r.get(COL_ID);
		return id;
	}
}
