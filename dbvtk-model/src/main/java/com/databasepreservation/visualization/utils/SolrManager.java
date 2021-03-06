package com.databasepreservation.visualization.utils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.response.UpdateResponse;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.roda.core.data.adapter.facet.Facets;
import org.roda.core.data.adapter.filter.Filter;
import org.roda.core.data.adapter.sort.Sorter;
import org.roda.core.data.adapter.sublist.Sublist;
import org.roda.core.data.exceptions.NotFoundException;
import org.roda.core.data.exceptions.RequestNotValidException;
import org.roda.core.data.v2.index.IndexResult;
import org.roda.core.data.v2.index.IsIndexed;
import org.roda.core.data.v2.user.RodaUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.databasepreservation.visualization.client.SavedSearch;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerDatabase;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerRow;
import com.databasepreservation.visualization.client.ViewerStructure.ViewerTable;
import com.databasepreservation.visualization.exceptions.ViewerException;
import com.databasepreservation.visualization.shared.ViewerSafeConstants;
import com.databasepreservation.visualization.transformers.SolrTransformer;

/**
 * Exposes some methods to interact with a Solr Server connected through HTTP
 * 
 * @author Bruno Ferreira <bferreira@keep.pt>
 */
public class SolrManager {
  private static final Logger LOGGER = LoggerFactory.getLogger(SolrManager.class);
  private static final long INSERT_DOCUMENT_TIMEOUT = 60000; // 60 seconds
  private static final int MAX_BUFFERED_DOCUMENTS_PER_COLLECTION = 10;
  private static final int MAX_BUFFERED_COLLECTIONS = 10;

  private final HttpSolrClient client;
  private final Set<String> collectionsToCommit;
  // private final LinkedHashMap<String, String> tablesUUIDandName = new
  // LinkedHashMap<>();
  private Map<String, List<SolrInputDocument>> docsByCollection = new HashMap<>();
  private boolean setupDone = false;

  public SolrManager(String url) {
    client = new HttpSolrClient(url);
    client.setConnectionTimeout(5000);
    // allowCompression defaults to false.
    // Server side must support gzip or deflate for this to have any effect.
    // solrDBList.setAllowCompression(true);

    // TODO: ensure that solr is running in cloud mode before execution

    collectionsToCommit = new HashSet<>();
  }

  /**
   * Adds a database to the databases collection and asynchronously creates
   * collections for its tables
   * 
   * @param database
   *          the new database
   */
  public void addDatabase(ViewerDatabase database) throws ViewerException {
    // creates databases collection, skipping if it is present
    CollectionAdminRequest.Create request = new CollectionAdminRequest.Create();
    request.setCollectionName(ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME);
    request.setConfigName(ViewerSafeConstants.SOLR_CONFIGSET_DATABASE);
    request.setNumShards(1);
    try {
      NamedList<Object> response = client.request(request);
    } catch (SolrServerException | IOException e) {
      throw new ViewerException("Error creating collection " + ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME,
        e);
    } catch (HttpSolrClient.RemoteSolrException e) {
      if (e.getMessage().contains(
        "collection already exists: " + ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME)) {
        LOGGER.info("collection " + ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME + " already exists.");
      } else {
        throw new ViewerException("Error creating collection "
          + ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME, e);
      }
    }

    // add this database to the collection
    collectionsToCommit.add(ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME);
    insertDocument(ViewerSafeConstants.SOLR_INDEX_DATABASE_COLLECTION_NAME, SolrTransformer.fromDatabase(database));

    // create collection needed to store saved searches
    try {
      createSavedSearchesCollection();
    } catch (ViewerException e) {
      LOGGER.error("Error creating saved searches collection", e);
    }

    // prepare tables to create the collection
    // for (ViewerSchema viewerSchema : database.getMetadata().getSchemas()) {
    // for (ViewerTable viewerTable : viewerSchema.getTables()) {
    // tablesUUIDandName.put(viewerTable.getUUID(), viewerTable.getName());
    // }
    // }
  }

  /**
   * Creates a new table collection in solr for the specified table
   *
   * @param table
   *          the table which data is going to be saved in this collections
   */
  public void addTable(ViewerTable table) throws ViewerException {
    String collectionName = SolrUtils.getTableCollectionName(table.getUUID());
    CollectionAdminRequest.Create request = new CollectionAdminRequest.Create();
    request.setCollectionName(collectionName);
    request.setConfigName(ViewerSafeConstants.SOLR_CONFIGSET_TABLE);
    request.setNumShards(1);

    try {
      LOGGER.info("Creating collection for table " + table.getName() + " with id " + table.getUUID());
      NamedList<Object> response = client.request(request);
      LOGGER.debug("Response from server (create collection for table with id " + table.getUUID() + "): "
        + response.toString());
      Object duration = response.findRecursive("responseHeader", "QTime");
      if (duration != null) {
        LOGGER.info("Created in " + duration + " ms");
      }
    } catch (HttpSolrClient.RemoteSolrException e) {
      LOGGER.error("Error in Solr server while creating collection " + collectionName, e);
    } catch (Exception e) {
      // mainly: SolrServerException and IOException
      LOGGER.error("Error creating collection " + collectionName, e);
    }

    collectionsToCommit.add(collectionName);
  }

  public void addRow(ViewerTable table, ViewerRow row) throws ViewerException {
    String collectionName = SolrUtils.getTableCollectionName(table.getUUID());
    insertDocument(collectionName, SolrTransformer.fromRow(table, row));
  }

  /**
   * Commits all changes to all modified collections and optimizes them
   *
   * @throws ViewerException
   */
  public void commitAll() throws ViewerException {
    for (String collection : collectionsToCommit) {
      commitAndOptimize(collection);
    }
    collectionsToCommit.clear();
  }

  /**
   * Frees resources created by this SolrManager object
   *
   * @throws ViewerException
   *           in case some resource could not be closed successfully
   */
  public void freeResources() throws ViewerException {
    try {
      client.close();
    } catch (IOException e) {
      throw new ViewerException(e);
    }
  }

  public <T extends IsIndexed> IndexResult<T> find(RodaUser user, Class<T> classToReturn, Filter filter, Sorter sorter,
    Sublist sublist, Facets facets) throws org.roda.core.data.exceptions.GenericException, RequestNotValidException {
    return SolrUtils.find(client, classToReturn, filter, sorter, sublist, facets);
  }

  public <T extends IsIndexed> Long count(RodaUser user, Class<T> classToReturn, Filter filter)
    throws org.roda.core.data.exceptions.GenericException, RequestNotValidException {
    return SolrUtils.count(client, classToReturn, filter);
  }

  public <T extends IsIndexed> T retrieve(RodaUser user, Class<T> classToReturn, String id) throws NotFoundException,
    org.roda.core.data.exceptions.GenericException {
    return SolrUtils.retrieve(client, classToReturn, id);
  }

  public <T extends IsIndexed> IndexResult<T> findRows(RodaUser user, Class<T> classToReturn, String tableUUID,
    Filter filter, Sorter sorter, Sublist sublist, Facets facets)
    throws org.roda.core.data.exceptions.GenericException, RequestNotValidException {
    return SolrUtils.find(client, classToReturn, tableUUID, filter, sorter, sublist, facets);
  }

  public InputStream findRowsCSV(RodaUser user, String tableUUID, Filter filter, Sorter sorter, Sublist sublist,
    List<String> fields) throws org.roda.core.data.exceptions.GenericException, RequestNotValidException {
    return SolrUtils.findCSV(client, SolrUtils.getTableCollectionName(tableUUID), filter, sorter, sublist, fields);
  }

  public <T extends IsIndexed> Long countRows(RodaUser user, Class<T> classToReturn, String tableUUID, Filter filter)
    throws org.roda.core.data.exceptions.GenericException, RequestNotValidException {
    return SolrUtils.count(client, classToReturn, tableUUID, filter);
  }

  public <T extends IsIndexed> T retrieveRows(RodaUser user, Class<T> classToReturn, String tableUUID, String rowUUID)
    throws NotFoundException, org.roda.core.data.exceptions.GenericException {
    return SolrUtils.retrieve(client, classToReturn, tableUUID, rowUUID);
  }

  public void addSavedSearch(RodaUser user, SavedSearch savedSearch) throws NotFoundException,
    org.roda.core.data.exceptions.GenericException {
    try {
      createSavedSearchesCollection();
    } catch (ViewerException e) {
      LOGGER.error("Error creating saved searches collection", e);
    }

    SolrInputDocument doc = SolrTransformer.fromSavedSearch(savedSearch);

    try {
      client.add(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, doc);
      client.commit(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, true, true, true);
    } catch (SolrServerException e) {
      LOGGER.debug("SolrServerException while attempting to save search", e);
    } catch (IOException e) {
      LOGGER.debug("IOException while attempting to save search", e);
    }
  }

  public void editSavedSearch(RodaUser user, String uuid, String name, String description) throws NotFoundException,
    org.roda.core.data.exceptions.GenericException {
    try {
      createSavedSearchesCollection();
    } catch (ViewerException e) {
      LOGGER.error("Error creating saved searches collection", e);
    }

    SolrInputDocument doc = new SolrInputDocument();

    doc.addField(ViewerSafeConstants.SOLR_SEARCHES_ID, uuid);
    doc.addField(ViewerSafeConstants.SOLR_SEARCHES_NAME, SolrUtils.asValueUpdate(name));
    doc.addField(ViewerSafeConstants.SOLR_SEARCHES_DESCRIPTION, SolrUtils.asValueUpdate(description));

    try {
      client.add(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, doc);
      client.commit(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, true, true);
    } catch (SolrServerException e) {
      LOGGER.debug("SolrServerException while attempting to save search", e);
    } catch (IOException e) {
      LOGGER.debug("IOException while attempting to save search", e);
    }
  }

  public void deleteSavedSearch(RodaUser user, String uuid) throws NotFoundException,
    org.roda.core.data.exceptions.GenericException {
    try {
      client.deleteById(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, uuid);
      client.commit(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, true, true);
    } catch (SolrServerException e) {
      LOGGER.debug("SolrServerException while attempting to delete search", e);
    } catch (IOException e) {
      LOGGER.debug("IOException while attempting to delete search", e);
    }
  }

  private void createSavedSearchesCollection() throws ViewerException {
    // creates saved searches collection, skipping if it is present
    CollectionAdminRequest.Create request = new CollectionAdminRequest.Create();
    request.setCollectionName(ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME);
    request.setConfigName(ViewerSafeConstants.SOLR_CONFIGSET_SEARCHES);
    request.setNumShards(1);
    try {
      NamedList<Object> response = client.request(request);
    } catch (SolrServerException | IOException e) {
      throw new ViewerException("Error creating collection " + ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME,
        e);
    } catch (HttpSolrClient.RemoteSolrException e) {
      if (e.getMessage().contains(
        "collection already exists: " + ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME)) {
        LOGGER.info("collection " + ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME + " already exists.");
      } else {
        throw new ViewerException("Error creating collection "
          + ViewerSafeConstants.SOLR_INDEX_SEARCHES_COLLECTION_NAME, e);
      }
    }
  }

  private void insertDocument(String collection, SolrInputDocument doc) throws ViewerException {
    if (doc == null) {
      throw new ViewerException("Attempted to insert null document into collection " + collection);
    }

    // add document to buffer
    if (!docsByCollection.containsKey(collection)) {
      docsByCollection.put(collection, new ArrayList<SolrInputDocument>());
    }
    List<SolrInputDocument> docs = docsByCollection.get(collection);
    // LOGGER.info("~~ AddedBatch doc to collection " + collection);
    docs.add(doc);

    // if buffer limit has been reached, "flush" it to solr
    if (docs.size() >= MAX_BUFFERED_DOCUMENTS_PER_COLLECTION || docsByCollection.size() >= MAX_BUFFERED_COLLECTIONS) {
      insertPendingDocuments();
    }
  }

  /**
   * The collections are not immediately available after creation, this method
   * makes sequential attempts to insert a document before giving up (by
   * timeout)
   *
   * @throws ViewerException
   *           in case of a fatal error
   */
  private void insertPendingDocuments() throws ViewerException {
    long timeoutStart = System.currentTimeMillis();
    int tries = 0;
    boolean insertedAllDocuments;
    do {
      UpdateResponse response = null;
      try {
        // add documents, because the buffer limits were reached
        for (String currentCollection : docsByCollection.keySet()) {
          List<SolrInputDocument> docs = docsByCollection.get(currentCollection);
          if (!docs.isEmpty()) {
            try {
              response = client.add(currentCollection, docs);
              if (response.getStatus() == 0) {
                // LOGGER.info("~~ Inserted " + docs.size() +
                // " into collection " + currentCollection);
                collectionsToCommit.add(currentCollection);
                docs.clear();
                // reset the timeout when something is inserted
                timeoutStart = System.currentTimeMillis();
              } else {
                LOGGER.warn("Could not insert a document batch in collection " + currentCollection + ". Response: "
                  + response.toString());
              }
            } catch (HttpSolrClient.RemoteSolrException e) {
              if (e.getMessage().contains("<title>Error 404 Not Found</title>")) {
                // this means that the collection does not exist yet. retry
                LOGGER.debug("Collection " + currentCollection + " does not exist (yet). Retrying (" + tries + ")");
              } else {
                LOGGER.warn("Could not insert a document batch in collection" + currentCollection
                  + ". Last response (if any): " + String.valueOf(response), e);
              }
            }
          }
        }
      } catch (SolrServerException | IOException e) {
        throw new ViewerException("Problem adding or committing information", e);
      }

      // check if something still needs to be inserted
      insertedAllDocuments = true;
      for (List<SolrInputDocument> docs : docsByCollection.values()) {
        if (!docs.isEmpty()) {
          insertedAllDocuments = false;
          break;
        }
      }

      if (!insertedAllDocuments) {
        // wait a moment and then retry (or reach timeout and fail)
        try {
          Thread.sleep(1000);
        } catch (InterruptedException e) {
          LOGGER.debug("insertDocument sleep was interrupted", e);
        }
        tries++;
      }
    } while (System.currentTimeMillis() - timeoutStart < INSERT_DOCUMENT_TIMEOUT && !insertedAllDocuments);

    if (insertedAllDocuments) {
      docsByCollection.entrySet().removeIf(new Predicate<Map.Entry<String, List<SolrInputDocument>>>() {
        @Override
        public boolean test(Map.Entry<String, List<SolrInputDocument>> entry) {
          return entry.getValue() == null || entry.getValue().isEmpty();
        }
      });
    } else {
      throw new ViewerException(
        "Could not insert a document batch in collection. Reason: Timeout reached while waiting for collection to be available.");
    }
  }

  private void commitAndOptimize(String collection) throws ViewerException {
    // insert any pending documents before optimizing
    insertPendingDocuments();
    commit(collection, true);
  }

  /**
   * The collections are not immediately available after creation, this method
   * makes sequential attempts to commit (and possibly optimize) a collection
   * before giving up (by timeout)
   *
   * @throws ViewerException
   *           in case of a fatal error
   */
  private void commit(String collection, boolean optimize) throws ViewerException {
    long timeoutStart = System.currentTimeMillis();
    int tries = 0;
    while (System.currentTimeMillis() - timeoutStart < INSERT_DOCUMENT_TIMEOUT) {
      UpdateResponse response;
      try {
        // commit
        try {
          response = client.commit(collection);
          if (response.getStatus() != 0) {
            throw new ViewerException("Could not commit collection " + collection);
          }
        } catch (SolrServerException | IOException e) {
          throw new ViewerException("Problem committing collection " + collection, e);
        }

        if (optimize) {
          try {
            response = client.optimize(collection);
            if (response.getStatus() == 0) {
              return;
            } else {
              throw new ViewerException("Could not optimize collection " + collection);
            }
          } catch (SolrServerException | IOException e) {
            throw new ViewerException("Problem optimizing collection " + collection, e);
          }
        } else {
          return;
        }
      } catch (HttpSolrClient.RemoteSolrException e) {
        if (e.getMessage().contains("<title>Error 404 Not Found</title>")) {
          // this means that the collection does not exist yet. retry
          LOGGER.debug("Collection " + collection + " does not exist. Retrying (" + tries + ")");
        } else {
          throw e;
        }
      }

      try {
        Thread.sleep(1000);
      } catch (InterruptedException e) {
        LOGGER.debug("insertDocument sleep was interrupted", e);
      }
      tries++;
    }

    // INSERT_DOCUMENT_TIMEOUT reached
    if (optimize) {
      throw new ViewerException("Failed to commit and optimize collection " + collection
        + ". Reason: Timeout reached while waiting for collection to be available, ran " + tries + " attempts.");
    } else {
      throw new ViewerException("Failed to commit collection " + collection
        + ". Reason: Timeout reached while waiting for collection to be available, ran " + tries + " attempts.");
    }

  }
}
