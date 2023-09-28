package com.igsl;

import java.io.FileWriter;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.text.StringEscapeUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.igsl.config.Config;
import com.igsl.export.ObjectExport;
import com.igsl.handler.Handler;
import com.igsl.handler.HandlerResult;

public class ConfluenceURLTransform {
	private static final String PATH_DELIM = FileSystems.getDefault().getSeparator();
	private static final Logger LOGGER = LogManager.getLogger(ConfluenceURLTransform.class);
	private static final String QUERY = 
			"SELECT bc.BODYCONTENTID, bc.BODY, s.SPACEKEY, c.TITLE " + 
			"FROM BODYCONTENT bc " + 
			"JOIN CONTENT c ON c.CONTENTID = bc.CONTENTID AND c.PREVVER IS NULL " + 
			"JOIN SPACES s ON s.SPACEID = c.SPACEID " + 
			"WHERE bc.BODY LIKE '%href=\"%'";
	private static final String UPDATE = 
			"UPDATE BODYCONTENT SET BODY = ? WHERE BODYCONTENTID = ?";
	private static final ObjectMapper OM = new ObjectMapper();
	private static final Pattern URL_PATTERN = 
			Pattern.compile("(<a\\s+(?:[^>]*?)?href\\s*=\\s*([\"']))(.*?)(\\2(?:[^>]*?)>(.*?)<\\/a>)");
	// Capturing groups 1 + 3 + 4 forms the whole pattern
	private static final int GROUP_BEFORE_HREF = 1;
	private static final int GROUP_HREF = 3;
	private static final int GROUP_AFTER_HREF = 4;
	private static final int GROUP_TEXT = 5;
	
	private static void closeConnection(Connection conn) {
		if (conn != null) {
			try {
				conn.close();
			} catch (Exception ex) {
				LOGGER.error("Failed to close connection", ex);
			}
		}
	}
	
	private static void urlTransform(Config config) {
		int pageCount = 0;	// No. of pages found with URLs
		int pageUpdatedCount = 0;	// No. of pages updated
		int pagePostMigrateCount = 0;	// No. of pages requiring post migration
		int urlCount = 0;	// No. of URLs found
		int urlError = 0;	// No. of invalid URLs
		int urlIgnoredCount = 0;	// No. of URLs ignored
		int urlUpdatedCount = 0;	// No. of URLs updated
		int urlPostMigrateCount = 0;	// No. of URLs requiring post migration
		try {
			long startTime = System.currentTimeMillis();
			if (config.getUrlTransform().isPerformUpdate()) {
				LOGGER.info("performUpdate is true, database will be updated");
			} else {
				LOGGER.info("performUpdate is false, database will not be updated");
			}
			String outputPrefix = System.getProperty("user.dir") + PATH_DELIM;
			if (config.getUrlTransform().getOutputDirectory() != null) {
				Path p = Paths.get(config.getUrlTransform().getOutputDirectory());
				if (!Files.exists(p)) {
					p = Files.createDirectories(p);
					outputPrefix = p.toAbsolutePath().toString() + PATH_DELIM;
				} else {
					if (Files.isDirectory(p)) {
						outputPrefix = p.toAbsolutePath().toString() + PATH_DELIM;
					} else {
						LOGGER.error("Output directory \"" + config.getUrlTransform().getOutputDirectory() + "\" is not a directory, output file will be written to current directory");
					}
				}
			}
			String outputSuffix = "." + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".csv";
			String postMigrateList = outputPrefix + config.getUrlTransform().getPostMigrateListBaseName() + outputSuffix;
			String urlList = outputPrefix + config.getUrlTransform().getUrlListBaseName() + outputSuffix;
			String ignoreList = outputPrefix + config.getUrlTransform().getUrlIgnoredBaseName() + outputSuffix;
			String errorList = outputPrefix + config.getUrlTransform().getUrlErrorBaseName() + outputSuffix;
			try (
					CSVPrinter postMigratePrinter = new CSVPrinter(new FileWriter(postMigrateList), CSVFormat.DEFAULT);
					CSVPrinter urlPrinter = new CSVPrinter(new FileWriter(urlList), CSVFormat.DEFAULT);
					CSVPrinter ignorePrinter = new CSVPrinter(new FileWriter(ignoreList), CSVFormat.DEFAULT);
					CSVPrinter errorPrinter = new CSVPrinter(new FileWriter(errorList), CSVFormat.DEFAULT);
				) {
				LOGGER.info("Post migrate list will be written to: " + postMigrateList);
				LOGGER.info("URL list will be written to: " + urlList);
				LOGGER.info("Ignored URL list will be written to: " + ignoreList);
				postMigratePrinter.printRecord("SPACEKEY", "TITLE", "BODYCONTENTID");
				urlPrinter.printRecord("POSTMIGRATE", "SPACEKEY", "TITLE", "BODYCONTENTID", "HANDLER", "FROM", "TO");
				ignorePrinter.printRecord("SPACEKEY", "TITLE", "BODYCONTENTID", "URL");
				errorPrinter.printRecord("SPACEKEY", "TITLE", "BODYCONTENTID", "URL", "ERRORMESSAGE", "HANDLER");
				// Create handlers
				List<Handler> handlers = new ArrayList<>();
				for (String handlerName : config.getUrlTransform().getHandlers()) {
					try {
						Handler h = (Handler) Class.forName(handlerName)
								.getDeclaredConstructor(Config.class).newInstance(config);
						handlers.add(h);
					} catch (Exception ex) {
						LOGGER.error("Unable to create handler " + handlerName, ex);
					}
				}
				Connection confluenceConn = config.getConnections().getConfluenceConnection();
				Connection jiraConn = config.getConnections().getJiraConnection();
				try (PreparedStatement query = confluenceConn.prepareStatement(QUERY)) {
					ResultSet rs = query.executeQuery();
					while (rs.next()) {
						pageCount++;
						String id = rs.getString(1);
						LOGGER.debug("Processing body content ID: " + id);
						String body = rs.getString(2);
						String spaceKey = rs.getString(3);
						String title = rs.getString(4);
						Matcher matcher = URL_PATTERN.matcher(body);
						StringBuilder sb = new StringBuilder();
						boolean changed = false;
						boolean postMigrate = false;
						while (matcher.find()) {
							urlCount++;
							String tag = matcher.group(0);
							String urlString = matcher.group(GROUP_HREF);
							String urlText = matcher.group(GROUP_TEXT);
							String handlerName = "N/A";
							try {
								String urlDecoded = StringEscapeUtils.unescapeHtml4(urlString);
								URI uri = new URI(urlDecoded);
								boolean accepted = false;
								for (Handler handler : handlers) {
									if (handler.accept(uri)) {
										handlerName = handler.getClass().getCanonicalName();
										HandlerResult hr = handler.handle(uri, urlText);
										switch (hr.getResultType()) {
										case ERROR:
											urlError++;
											errorPrinter.printRecord(spaceKey, title, id, urlString, hr.getErrorMessage(), handlerName);
											break;
										case TAG: 
											accepted = true;
											changed = true;
											urlUpdatedCount++;
											LOGGER.debug(
													handler.getClass() + ": " + 
													"ID: [" + id + "] " + 
													"From: [" + tag + "] " + 
													"To: [" + hr.getTag() + "]");
											// Replace the whole match
											matcher.appendReplacement(sb, Matcher.quoteReplacement(hr.getTag()));
											urlPrinter.printRecord(
													handler.needPostMigrate(), spaceKey, title, id, 
													handler.getClass(),
													tag, hr.getTag());
											break;
										case URI: 
											accepted = true;
											changed = true;
											urlUpdatedCount++;
											String resultUrl = StringEscapeUtils.escapeHtml4(hr.getUri().toString());
											LOGGER.debug(
													handler.getClass() + ": " + 
													"ID: [" + id + "] " + 
													"From URL: [" + urlString + "] " + 
													"Decoded URL: [" + urlDecoded + "] " + 
													"Path: [" + uri.getPath() + "] " + 
													"Query: [" + uri.getQuery() + "] " + 
													"To URL: [" + hr.getUri().toString() + "] " +  
													"To Escaped URL: [" + resultUrl + "]");
											// Replace URL only
											matcher.appendReplacement(
													sb, "$" + GROUP_BEFORE_HREF + 
													Matcher.quoteReplacement(resultUrl) +
													"$" + GROUP_AFTER_HREF);
											urlPrinter.printRecord(
													handler.needPostMigrate(), spaceKey, title, id, 
													handlerName,
													urlString, resultUrl);
											break;
										}
										if (accepted && handler.needPostMigrate()) {
											postMigrate = true;
											urlPostMigrateCount++;
										}
										break;	// Stop after a handler accepts the URL
									}
								}
								if (!accepted) {
									urlIgnoredCount++;
									ignorePrinter.printRecord(spaceKey, title, id, urlString);
									LOGGER.debug("Unaccepted URL: " + urlString);
								}
							} catch (Exception ex) {
								LOGGER.debug("Ignoring invalid URI: " + urlString);
								urlError++;
								errorPrinter.printRecord(spaceKey, title, id, urlString, ex.getMessage(), handlerName);
							}
						}	// While matcher.find
						matcher.appendTail(sb);
						if (postMigrate) {
							postMigratePrinter.printRecord(spaceKey, title, id);
							pagePostMigrateCount++;
						}
						if (changed) {
							pageUpdatedCount++;
							LOGGER.debug(
									"ID: " + id + " " + 
									"Source: [" + body + "] " + 
									"Result: [" + sb.toString() + "]");
							if (config.getUrlTransform().isPerformUpdate()) {
								try (PreparedStatement update = confluenceConn.prepareStatement(UPDATE)) {
									update.setString(1, sb.toString());
									update.setString(2, id);
									int count = update.executeUpdate();
									if (count == 1) {
										LOGGER.debug("ID: " + id + " Updated successfully");
										confluenceConn.commit();
									} else {
										LOGGER.debug("ID: " + id + " Update failed, more than 1 row got updated, rolling back");
										confluenceConn.rollback();
									}
								} catch (Exception ex) {
									ex.printStackTrace();
									LOGGER.error("ID: " + id + " Update failed, rolling back");
									confluenceConn.rollback();
								}
							}
							/*
							 * NOTE: 
							 * Need to flush cache on content objects or restart server to show new values.
							 * 
							 * Drafts are also stored in BODYCONTENT and need to be updated.
							 * Otherwise you will open the editor and see old link values.
							 * But since drafts are not migrated... leave this for later.
							 * 
							 * Reference: 
							 * https://confluence.atlassian.com/confkb/how-to-bulk-update-confluence-content-through-the-database-to-replace-old-macros-with-new-ones-1018780615.html
							 * https://confluence.atlassian.com/confkb/how-do-drafts-work-on-confluence-938043306.html
							 * 
							 * To find the drafts:
							 * 	
							  	SELECT cp.CONTENTID, bc.body, c.*
								FROM CONTENTPROPERTIES cp
								JOIN CONTENT c ON c.CONTENTID = cp.CONTENTID and c.CONTENT_STATUS = 'draft'
								JOIN BODYCONTENT bc ON bc.CONTENTID = cp.CONTENTID
								JOIN CONTENTPROPERTIES cpMain ON cp.STRINGVAL = cpMain.STRINGVAL AND cpMain.PROPERTYNAME = 'share-id'
								WHERE cp.PROPERTYNAME = 'share-id' 						
								AND cpMain.CONTENTID = <ID updated>
								AND cp.CONTENTID != cpMain.CONTENTID;
							 * 
							 * The draft record remains after publishing.
							 */
						}	// If changed
					}	// ResultSet loop
				}	// Try PreparedStatement
			} catch (Exception ex) {
				LOGGER.error("Error", ex);
			}
			long stopTime = System.currentTimeMillis();
			LOGGER.info("Execution time: From " + startTime + " to " + stopTime + ", elapsed: " + (stopTime - startTime));
			LOGGER.info("Page count: " + pageCount);
			LOGGER.info("Page updated: " + pageUpdatedCount);
			LOGGER.info("URL count: " + urlCount);
			LOGGER.info("Invalid URL: " + urlError);
			LOGGER.info("URL updated: " + urlUpdatedCount);
			LOGGER.info("URL ignored: " + urlIgnoredCount);
			LOGGER.info("Page requiring post migration (among page updated): " + pagePostMigrateCount);
			LOGGER.info("URL requiring post migration (among url updated): " + urlPostMigrateCount);
		} catch (Exception ex) {
			LOGGER.error("Error", ex);
		}
	}
	
	private static void exportObjects(Config config) throws Exception {
		String outputPrefix = ".";
		if (config.getObjectExport().getOutputDirectory() != null) {
			Path p = Paths.get(config.getObjectExport().getOutputDirectory());
			if (!Files.exists(p)) {
				p = Files.createDirectories(p);
				outputPrefix = p.toAbsolutePath().toString() + PATH_DELIM;
			} else {
				if (Files.isDirectory(p)) {
					outputPrefix = p.toAbsolutePath().toString() + PATH_DELIM;
				} else {
					LOGGER.error("Output directory \"" + config.getObjectExport().getOutputDirectory() + "\" is not a directory, output file will be written to current directory");
				}
			}
		}
		List<ObjectExport> exporters = new ArrayList<>(); 
		for (String exporterName : config.getObjectExport().getHandlers()) {
			try {
				ObjectExport exporter = (ObjectExport) Class.forName(exporterName)
						.getDeclaredConstructor().newInstance();
				exporters.add(exporter);
			} catch (Exception ex) {
				LOGGER.error("Unable to create object exporter " + exporterName, ex);
			}
		}
		for (ObjectExport exporter : exporters) {
			try {
				Path p = exporter.exportObjects(config);
				LOGGER.info(exporter.getClass().getSimpleName() + ": objects written to " + p.toFile().getAbsolutePath());
			} catch (Exception ex) {
				LOGGER.error("Error", ex);
			}
		}
	}
	
	public static void main(String[] args) {
		Connection confluenceConn = null;
		Connection jiraConn = null;
		try {
			// Read config
			ObjectReader or = OM.readerFor(Config.class);
			Config config = new Config();
			try (InputStream in = ClassLoader.getSystemClassLoader().getResourceAsStream("Config.json")) {
				config = or.readValue(in);
			}
			config.validate();
			// Get password
			if (config.getConnections().getConfluencePassword() == null) {
				Console.println("For Confluence database at: %1s\n", config.getConnections().getConfluenceConnectionString());
				if (config.getConnections().getConfluenceUser() == null) {
					config.getConnections().setConfluenceUser(Console.readLine("Confluence User: "));
				}else {
					Console.println("Confluence User: %1s", config.getConnections().getConfluenceUser());
				}
				config.getConnections().setConfluencePassword(new String(Console.readPassword("Confluence Password: ")));
			}
			if (config.getConnections().getJiraPassword() == null) {
				Console.println("For Jira database at: %1s\n", config.getConnections().getJiraConnectionString());
				if (config.getConnections().getJiraUser() == null) {
					config.getConnections().setJiraUser(Console.readLine("Jira User: "));
				} else {
					Console.println("Jira User: %1s", config.getConnections().getJiraUser());
				}
				config.getConnections().setJiraPassword(new String(Console.readPassword("Jira Password: ")));
			}
			// Get DB connections
			if (config.getConnections().getConfluenceConnectionString() != null) {
				confluenceConn = DriverManager.getConnection(
						config.getConnections().getConfluenceConnectionString(),
						config.getConnections().getConfluenceUser(),
						config.getConnections().getConfluencePassword());
				confluenceConn.setAutoCommit(false);
			}
			if (config.getConnections().getJiraConnectionString() != null) {
				jiraConn = DriverManager.getConnection(
					config.getConnections().getJiraConnectionString(),
					config.getConnections().getJiraUser(),
					config.getConnections().getJiraPassword());
				jiraConn.setAutoCommit(false);
			}
			// Store in config
			config.getConnections().setConfluenceConnection(confluenceConn);
			config.getConnections().setJiraConnection(jiraConn);
			// Execute
			urlTransform(config);
			exportObjects(config);
		} catch (Exception ex) {
			LOGGER.error("Error", ex);
		} finally {
			closeConnection(jiraConn);
			closeConnection(confluenceConn);
		}
	}
}
