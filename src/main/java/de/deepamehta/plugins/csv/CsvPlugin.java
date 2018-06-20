package de.deepamehta.plugins.csv;

import au.com.bytecode.opencsv.CSVReader;
import de.deepamehta.core.AssociationDefinition;
import de.deepamehta.core.Topic;
import de.deepamehta.core.TopicType;
import de.deepamehta.core.model.ChildTopicsModel;
import de.deepamehta.core.model.TopicModel;
import de.deepamehta.core.osgi.PluginActivator;
import de.deepamehta.core.service.Inject;
import de.deepamehta.core.storage.spi.DeepaMehtaTransaction;
import de.deepamehta.files.FilesService;

import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;
import java.util.logging.Logger;

/**
 * A plugin to map lines of a .CSV file (TAB-separated) to instances of a simple Topic Type.
 * Where simple means that the Topic Type Definition must not have another _Composite_ as child (levels of depth
 * supported not more than one) but  can have many simple child Topic Types (e.g. Text, Number, Boolean, HTML).<br/><br/>
 *
 * Note 1: Through relying on identifiers in the first column of the CSV-document
 * UPDATES on all values are supported across multiple uploads ("Import CSV" command on Topic Type).<br/><br/>
 *
 * Note 2: During the import process, simple "dm4.core.text" (and possibly numbers too) will be matched by value
 * and Topic Type URI. For that a matching topic is searched and automatically referenced if it's Type Definition
 * to the type imported to is "Aggregation Definition" (as the value is used and referenced in other topics too).
 *
 * @author Malte Rei&szlig;ig (<a href="mailto:malte@mikromedia.de">Email</a>), Danny Graf, 2012-2016
 * @version 0.0.7
 */
@Path("csv")
@Produces(MediaType.APPLICATION_JSON)
public class CsvPlugin extends PluginActivator {

    private static Logger log = Logger.getLogger(CsvPlugin.class.getName());

    public static final char SEPARATOR = '|';

    @Inject
    private FilesService fileService;

    @POST
    @Path("import/{uri}/{file}")
    public ImportStatus importCsv(@PathParam("uri") String typeUri, @PathParam("file") long fileId) {
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
                TopicModel model = mf.newTopicModel(typeUri);
                model.setUri(topicUri);

                // map all columns to composite value
                ChildTopicsModel value = mf.newChildTopicsModel();
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
                // this needs to be done in single transactions so referencing aggrated topics by value works
                // when they come all in one .csv file
                Long topicId = instancesOfUri.get(topicUri);
                DeepaMehtaTransaction tx = dm4.beginTx();
                if (topicId == null) { // create
                    dm4.createTopic(model);
                    created++;
                    tx.success();
                } else { // update topic and remove from map (in java memory)
                    model.setId(topicId);
                    instancesOfUri.remove(topicUri);
                    dm4.updateTopic(model);
                    updated++;
                    tx.success();
                }
                tx.finish();
            }

            // delete the remaining instances
            DeepaMehtaTransaction tx = dm4.beginTx();
            for (String topicUri : instancesOfUri.keySet()) {
                Long topicId = instancesOfUri.get(topicUri);
                dm4.deleteTopic(topicId);
                deleted++;
            }
            tx.success();
            tx.finish();

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
        TopicType topicType = dm4.getTopicType(typeUri);
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
        for (Topic instance : dm4.getTopicsByType(childTypeUri)) {
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
        for (Topic topic : dm4.getTopicsByType(typeUri)) {
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

}
