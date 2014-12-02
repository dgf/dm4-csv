package de.deepamehta.plugins.csv;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.WebApplicationException;

import au.com.bytecode.opencsv.CSVReader;
import de.deepamehta.core.AssociationDefinition;
import de.deepamehta.core.RelatedTopic;
import de.deepamehta.core.TopicType;
import de.deepamehta.core.model.ChildTopicsModel;
import de.deepamehta.core.model.TopicModel;
import de.deepamehta.core.osgi.PluginActivator;
import de.deepamehta.core.service.Inject;
import de.deepamehta.core.service.PluginService;
import de.deepamehta.core.service.Transactional;
import de.deepamehta.plugins.files.ResourceInfo;
import de.deepamehta.plugins.files.service.FilesService;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("csv")
@Produces(MediaType.APPLICATION_JSON)
public class CsvPlugin extends PluginActivator {

    private static Logger log = Logger.getLogger(CsvPlugin.class.getName());

    public static final String FOLDER = "csv";

    public static final char SEPARATOR = '|';

    private boolean isInitialized;
    
    @Inject
    private FilesService fileService;

    @POST
    @Transactional
    @Path("import/{uri}/{file}")
    public ImportStatus importCsv(//
            @PathParam("uri") String typeUri,//
            @PathParam("file") long fileId) {

        try {
            List<String[]> lines = readCsvFile(fileId);
            if (lines.size() < 2) {
                return new ImportStatus(false, "please upload a valid CSV, see README", null);
            }

            // read in typeUris of childTopics
            List<String> childTypeUris = Arrays.asList(lines.get(0));
            String uriPrefix = childTypeUris.get(0);
            
            // 
            Map<String, Long> instancesOfUri = getTopicsByTypeWithURIPrefix(typeUri, uriPrefix);
            Map<String, Map<String, Long>> aggrIdsByTypeUriAndValue = getPossibleAggrChilds(typeUri, childTypeUris);

            // status informations
            int created = 0, deleted = 0, updated = 0;

            // persist each row
            for (int r = 1; r < lines.size(); r++) {
                String[] row = lines.get(r);
                String topicUri = uriPrefix + "." + row[0];

                // create a fresh model
                TopicModel model = new TopicModel(typeUri);
                model.setUri(topicUri);

                // map all columns to composite value
                ChildTopicsModel value = new ChildTopicsModel();
                for (int c = 1; c < row.length; c++) {
                    String childTypeUri = childTypeUris.get(c);
                    String childValue = row[c];

                    // reference or create a child
                    Map<String, Long> aggrIdsByValue = aggrIdsByTypeUriAndValue.get(childTypeUri);
                    if (aggrIdsByValue != null && aggrIdsByValue.get(childValue) != null) {
                        value.putRef(childTypeUri, aggrIdsByValue.get(childValue));
                    } else {
                        value.put(childTypeUri, childValue);
                    }
                }
                model.setChildTopicsModel(value);

                // create or update a topic
                Long topicId = instancesOfUri.get(topicUri);
                if (topicId == null) { // create
                    dms.createTopic(model);
                    created++;
                } else { // update topic and remove from map (in java memory)
                    model.setId(topicId);
                    instancesOfUri.remove(topicUri);
                    dms.updateTopic(model);
                    updated++;
                }
            }

            // delete the remaining instances
            for (String topicUri : instancesOfUri.keySet()) {
                Long topicId = instancesOfUri.get(topicUri);
                dms.deleteTopic(topicId);
                deleted++;
            }

            List<String> status = new ArrayList<String>();
            status.add("created: " + created);
            status.add("updated: " + updated);
            status.add("deleted: " + deleted);

            return new ImportStatus(true, "SUCCESS", status);
        } catch (IOException e) {
            throw new RuntimeException(e) ;
        }
    }

    /**
     * get all possible aggregation instances and hash them by typeUri and value
     * 
     * @param typeUri
     * @param childTypeUris
     * @return
     */
    private Map<String, Map<String, Long>> getPossibleAggrChilds(String typeUri, List<String> childTypeUris) {
        TopicType topicType = dms.getTopicType(typeUri);
        Map<String, Map<String, Long>> aggrIdsByTypeUriAndValue = new HashMap<String, Map<String, Long>>();
        for (AssociationDefinition associationDefinition : topicType.getAssocDefs()) {
            if (associationDefinition.getTypeUri().equals("dm4.core.aggregation_def")) {
                String childTypeUri = associationDefinition.getChildTypeUri();
                if (childTypeUris.contains(childTypeUri)) {
                    aggrIdsByTypeUriAndValue.put(childTypeUri, getTopicIdsByValue(childTypeUri));
                }
            }
        }
        return aggrIdsByTypeUriAndValue;
    }

    /**
     * get all existing instance topics and hash them by value
     * 
     * @param childTypeUri
     * @return instance topics hashed by value
     */
    private Map<String, Long> getTopicIdsByValue(String childTypeUri) {
        Map<String, Long> idsByValue = new HashMap<String, Long>();
        for (RelatedTopic instance : dms.getTopics(childTypeUri, 0).getItems()) {
            idsByValue.put(instance.getSimpleValue().toString(), instance.getId());
        }
        return idsByValue;
    }

    /**
     * get all existing instance topics and hash them by URI
     * 
     * @param typeUri
     * @return instance topics hashed by URI
     */
    private Map<String, Long> getTopicsByTypeWithURIPrefix(String typeUri, String uriPrefix) {
        Map<String, Long> idsByUri = new HashMap<String, Long>();
        for (RelatedTopic topic : dms.getTopics(typeUri, 0).getItems()) {
            String topicUri = topic.getUri();
            if (topicUri != null && topicUri.isEmpty() == false) {
                if (topicUri.startsWith(uriPrefix)) idsByUri.put(topicUri, topic.getId());
            }
        }
        return idsByUri;
    }

    /**
     * read and validate CSV file
     * 
     * @param fileId
     * @return parsed CSV rows with trimmed column array
     * 
     * @throws FileNotFoundException
     * @throws IOException
     */
    private List<String[]> readCsvFile(long fileId) throws IOException {
        String fileName = fileService.getFile(fileId).getAbsolutePath();
        log.info("read CSV " + fileName);
        CSVReader csvReader = new CSVReader(new FileReader(fileName), SEPARATOR);
        List<String[]> lines = csvReader.readAll();
        csvReader.close();

        // trim all columns
        for (String[] row : lines) {
            for (int col = 0; col < row.length; col++) {
                row[col] = row[col].trim();
            }
        }
        return lines;
    }

    /**
     * Initialize.
     */
    @Override
    public void init() {
        isInitialized = true;
        configureIfReady();
    }

    @Override
    public void serviceArrived(PluginService service) {
        if (service instanceof FilesService) {
            fileService = (FilesService) service;
        }
        configureIfReady();
    }

    @Override
    public void serviceGone(PluginService service) {
        if (service == fileService) {
            fileService = null;
        }
    }

    private void configureIfReady() {
        if (isInitialized && fileService != null) {
            createCsvDirectory();
        }
    }

    private void createCsvDirectory() {
        // TODO move the initialization to migration "0"
        try {
            ResourceInfo resourceInfo = fileService.getResourceInfo(FOLDER);
            String kind = resourceInfo.toJSON().getString("kind");
            if (kind.equals("directory") == false) {
                String repoPath = System.getProperty("dm4.filerepo.path");
                throw new IllegalStateException("CSV storage directory " + //
                        repoPath + File.separator + FOLDER + " can not be used");
            }
        } catch (WebApplicationException e) { // !exists
            // catch fileService info request error => create directory
            if (e.getResponse().getStatus() != 404) {
                throw e;
            } else {
                log.info("create CSV directory");
                fileService.createFolder(FOLDER, "/");
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
