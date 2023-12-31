package com.igsl.export.cloud;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
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
import com.igsl.Log;
import com.igsl.ObjectData;
import com.igsl.config.Config;
import com.igsl.export.dc.ObjectExport;
import com.igsl.rest.RESTUtil;

public abstract class BaseExport<T> {
	private static final Logger LOGGER = LogManager.getLogger(BaseExport.class);	
	
	public static final String COL_NOTE = "NOTE";
	public static final String NOTE_NO_MATCH = "No Match";
	public static final String NOTE_MULTIPLE_MATCHES = "Multiple Matches";
	public static final String NOTE_MATCHED = "Matched";	
	public static final String COL_DCID = "DC_ID";
	public static final String COL_DCKEY = "DC_KEY";
	public static final String COL_CLOUDID = "CLOUD_ID";
	public static final String COL_CLOUDKEY = "CLOUD_KEY";
	public static final List<String> COL_MAPPING = Arrays.asList(COL_NOTE, COL_DCID, COL_DCKEY, COL_CLOUDID, COL_CLOUDKEY);
	
	protected static Map<String, List<?>> dataCache = new HashMap<>();
	
	protected final Class<T> templateClass;
	public BaseExport(Class<T> templateClass) {
		this.templateClass = templateClass;
	}
	
	/**
	 * Store this BaseExport's data in cache or not
	 * @return
	 */
	@JsonIgnore
	protected boolean useDataCache() {
		return false;
	}
	@JsonIgnore
	public abstract String getLimitParameter();
	@JsonIgnore
	public abstract String getStartAtParameter();
	
	protected MultivaluedMap<String, Object> getAuthenticationHeader(Config config) throws Exception {
		return RESTUtil.getCloudAuthenticationHeader(config);
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
		return RESTUtil.invokeCloudRest(
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
		try (	FileReader fr = CSV.getCSVFileReader(p); 
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
		try (	FileReader fr = CSV.getCSVFileReader(p); 
				CSVParser parser = new CSVParser(fr, CSV.getCSVReadFormat())) {
			parser.forEach(new Consumer<CSVRecord>() {
				@Override
				public void accept(CSVRecord r) {
					String note = r.get(COL_NOTE);
					String dcId = r.get(COL_DCID);
					String cloudId = r.get(COL_CLOUDID);
					switch (note) {
					case NOTE_MATCHED:
						result.put(dcId, cloudId);
						break;
					default: 
						Log.warn(LOGGER, "Unmatched entry ignored: [" + dcId + "] = [" + cloudId + "]");
						break;
					}
				}
			});
		}
		return result;
	}
	
	/**
	 * If this BaseExport allows mapping 1 Cloud object to multiple DC/Server objects or not.
	 * Default is false.
	 * If allowed, then the same Cloud ID will be used for multiple DC/Server IDs. 
	 * @return
	 */
	protected boolean allowOneToManyMapping() {
		return false;
	};
	
	public Path[] exportObjects(Config config) throws Exception {
		// TODO How to handle mapping duplicate keys... e.g. user display name
		ObjectExport p = getObjectExport();
		p.setConfig(config);
		List<ObjectData> dcData = p.readObjects(config.getDcExportDirectory());
		Log.debug(LOGGER, "ObjectData: " + this.getClass().getSimpleName() + " count: " + dcData.size());
		Path csvPath = getOutputPath(config);
		Path mappingPath = getMappingPath(config);
		List<String> headers = getCSVHeaders();
		try (	FileWriter fwCSV = CSV.getCSVFileWriter(csvPath);
				CSVPrinter printerCSV = new CSVPrinter(fwCSV, CSV.getCSVWriteFormat(headers));
				FileWriter fwMapping = CSV.getCSVFileWriter(mappingPath);
				CSVPrinter printerMapping = new CSVPrinter(fwMapping, CSV.getCSVWriteFormat(COL_MAPPING))) {
			List<T> objects = getObjects(config);
			for (T obj : objects) {
				List<ObjectData> rows = getCSVRows(obj);
				if (rows != null) {
					for (ObjectData cloudItem : rows) {
						CSV.printRecord(printerCSV, cloudItem);
						List<ObjectData> matchesFound = new ArrayList<>();
						for (ObjectData dcItem : dcData) {
							if (dcItem.getUniqueKey().equals(cloudItem.getUniqueKey())) {
								matchesFound.add(dcItem);
							}
						}
						if (allowOneToManyMapping()) {
							for (ObjectData dcItem : matchesFound) {
								dcItem.setMapped(true);
								CSV.printRecord(printerMapping, 
										NOTE_MATCHED, 
										dcItem.getId(), dcItem.getUniqueKey(), 
										cloudItem.getId(), cloudItem.getUniqueKey());
							}
						} else {
							switch (matchesFound.size()) {
							case 0:
								// No match found
								CSV.printRecord(printerMapping, 
										NOTE_NO_MATCH, 
										"", "", 
										cloudItem.getId(), cloudItem.getUniqueKey());
								break;
							case 1:
								// Single match found
								ObjectData dcItem = matchesFound.get(0);
								dcItem.setMapped(true);
								CSV.printRecord(printerMapping, 
										NOTE_MATCHED, 
										dcItem.getId(), dcItem.getUniqueKey(), 
										cloudItem.getId(), cloudItem.getUniqueKey());
								break;
							default:
								// Multiple matches found
								CSV.printRecord(printerMapping, 
										NOTE_MULTIPLE_MATCHES, 
										"", "", 
										cloudItem.getId(), cloudItem.getUniqueKey());
								break;
							}
						}
					}
				}
			}
			// Add unmapped DC items
			for (ObjectData data : dcData) {
				if (!data.isMapped()) {
					CSV.printRecord(printerMapping, 
							NOTE_NO_MATCH, 
							data.getId(), data.getUniqueKey(),
							"", ""); 
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
	 * Wrapper over _getObjects to implement a cache
	 */
	@SuppressWarnings("unchecked")
	public List<T> getObjects(Config config) throws Exception {
		String className = this.getClass().getCanonicalName();
		if (useDataCache()) {
			if (dataCache.containsKey(className)) {
				Log.info(LOGGER, "Using cached data for " + className);
				return (List<T>) dataCache.get(className);
			} else {
				Log.info(LOGGER, "Using REST data for " + className);
				List<T> data = _getObjects(config);
				dataCache.put(className, data);
				return data;
			}
		} else {
			Log.info(LOGGER, "Using REST data for " + className);
			return _getObjects(config);
		}
	}
	
	/**
	 * Get data returned from Cloud REST API
	 */
	protected abstract List<T> _getObjects(Config config) throws Exception;	
}
