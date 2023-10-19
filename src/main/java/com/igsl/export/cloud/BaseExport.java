package com.igsl.export.cloud;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.igsl.CSV;
import com.igsl.ObjectData;
import com.igsl.config.Config;
import com.igsl.export.dc.ObjectExport;
import com.igsl.rest.RESTUtil;

public abstract class BaseExport<T> {
	private static final Logger LOGGER = LogManager.getLogger(BaseExport.class);	
	
	public static final String COL_DCID = "DC_ID";
	public static final String COL_DCKEY = "DC_KEY";
	public static final String COL_CLOUDID = "CLOUD_ID";
	public static final String COL_CLOUDKEY = "CLOUD_KEY";
	public static final List<String> COL_MAPPING = Arrays.asList(COL_DCID, COL_DCKEY, COL_CLOUDID, COL_CLOUDKEY);
	
	protected final Class<T> templateClass;
	public BaseExport(Class<T> templateClass) {
		this.templateClass = templateClass;
	}
	
	@JsonIgnore
	public abstract String getLimitParameter();
	@JsonIgnore
	public abstract String getStartAtParameter();
	
	protected MultivaluedMap<String, Object> getAuthenticationHeader(Config config) throws Exception {
		return RESTUtil.getAuthenticationHeader(config);
	}
	
	// Generic REST API invoke
	protected List<T> invokeRest(
			Config config, 
			String path, 
			String method, 
			MultivaluedMap<String, Object> headers, 
			Map<String, Object> queryParameters, 
			Object data,
			int... successStatuses) throws Exception {
		return RESTUtil.invokeRest(
				templateClass, config, 
				path, method, 
				headers, queryParameters, 
				data, 
				getStartAtParameter(), 
				successStatuses);
	}
	
	protected Path getOutputPath(String dir) throws IOException {
		return Paths.get(dir, this.getClass().getSimpleName() + ".csv");
	}
	protected Path getOutputPath(Config config) throws IOException {
		return getOutputPath(config.getOutputDirectory().toFile().getAbsolutePath());
	}
	
	public Path getMappingPath(String dir) throws IOException {
		return Paths.get(dir, this.getClass().getSimpleName() + "_Mapping.csv");
	}
	public Path getMappingPath(Config config) throws IOException {
		return getMappingPath(config.getOutputDirectory().toFile().getAbsolutePath());
	}
	
	protected abstract ObjectExport getObjectExport();
	
	/**
	 * Get mapping of Cloud unique key to Cloud id.
	 * The REST APIs tend to accept id only.
	 */
	public Map<String, String> readCloudUniqueKeyToIdMapping(Path dir) throws Exception {
		Map<String, String> result = new HashMap<>();
		Path p = getMappingPath(dir.toFile().getAbsolutePath());
		try (	FileReader fr = new FileReader(p.toFile()); 
				CSVParser parser = new CSVParser(fr, CSV.getCSVReadFormat())) {
			parser.forEach(new Consumer<CSVRecord>() {
				@Override
				public void accept(CSVRecord r) {
					String id = r.get(COL_CLOUDID);
					String key = r.get(COL_CLOUDKEY);
					result.put(key, id);
				}
			});
		}
		return result;
	}
	
	public Map<String, String> readDCIdtoCloudIdMapping(Path dir) throws Exception {
		Map<String, String> result = new HashMap<>();
		Path p = getMappingPath(dir.toFile().getAbsolutePath());
		try (	FileReader fr = new FileReader(p.toFile()); 
				CSVParser parser = new CSVParser(fr, CSV.getCSVReadFormat())) {
			parser.forEach(new Consumer<CSVRecord>() {
				@Override
				public void accept(CSVRecord r) {
					String dcId = r.get(COL_DCID);
					String cloudId = r.get(COL_CLOUDID);
					result.put(dcId, cloudId);
				}
			});
		}
		return result;
	}
	
	public Path[] exportObjects(Config config) throws Exception {
		// TODO How to handle mapping duplicate keys... e.g. user display name
		ObjectExport p = getObjectExport();
		p.setConfig(config);
		Map<String, ObjectData> dcData = p.readObjects(config.getDcExportDirectory());
		Path csvPath = getOutputPath(config);
		Path mappingPath = getMappingPath(config);
		List<String> headers = getCSVHeaders();
		try (	FileWriter fwCSV = new FileWriter(csvPath.toFile());
				CSVPrinter printerCSV = new CSVPrinter(fwCSV, CSV.getCSVWriteFormat(headers));
				FileWriter fwMapping = new FileWriter(mappingPath.toFile());
				CSVPrinter printerMapping = new CSVPrinter(fwMapping, CSV.getCSVWriteFormat(COL_MAPPING))) {
			List<T> objects = getObjects(config);
			for (T obj : objects) {
				List<ObjectData> rows = getCSVRows(obj);
				if (rows != null) {
					for (ObjectData row : rows) {
						if (dcData.containsKey(row.getUniqueKey())) {
							ObjectData dcObject = dcData.get(row.getUniqueKey());
							dcObject.setMapped(true);
							CSV.printRecord(printerMapping, 
									dcObject.getId(), dcObject.getUniqueKey(), row.getId(), row.getUniqueKey());
						} else {
							CSV.printRecord(printerMapping, "", "", row.getId(), row.getUniqueKey());
						}
						CSV.printRecord(printerCSV, row);
					}
				}
			}
			// Record unmapped DC items
			for (ObjectData data : dcData.values()) {
				if (!data.isMapped()) {
					CSV.printRecord(printerMapping, data.getId(), data.getUniqueKey(), "", "");
				}
			}
		}
		return new Path[] {csvPath, mappingPath};
	}
	
	/**
	 * Return CSV headers
	 */
	protected abstract List<String> getCSVHeaders();
	
	/**
	 * Convert a single item from getObjects() into a generic structure with unique identifier
	 */
	protected abstract List<ObjectData> getCSVRows(T obj);
	
	/**
	 * Get data returned from Cloud REST API
	 */
	public abstract List<T> getObjects(Config config) throws Exception;	
}