/*
 * RESTHeart - the data Repository API server
 * Copyright (C) SoftInstigate Srl
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.restheart.db;

import com.google.common.collect.Lists;
import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.bulk.BulkWriteResult;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import static com.mongodb.client.model.Filters.eq;
import com.mongodb.client.model.DeleteManyModel;
import com.mongodb.client.model.UpdateManyModel;
import com.mongodb.client.model.WriteModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.restheart.utils.HttpStatus;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Andrea Di Cesare {@literal <andrea@softinstigate.com>}
 */
public class DocumentDAO implements Repository {

    private final Logger LOGGER = LoggerFactory.getLogger(DocumentDAO.class);

    private final MongoClient client;

    public DocumentDAO() {
        client = MongoDBClientSingleton.getInstance().getClient();
    }

    @Override
    public Document getDocumentEtag(final String dbName, final String collName, final Object documentId) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        FindIterable<Document> documents = mcoll
                .find(eq("_id", documentId))
                .projection(new Document("_etag", 1));

        return documents == null ? null
                : documents.iterator().tryNext();
    }

    /**
     * @param dbName
     * @param collName
     * @param documentId
     * @param newContent
     * @param requestEtag
     * @param patching
     * @param checkEtag
     * @return the HttpStatus code
     */
    @Override
    public OperationResult upsertDocument(
            final String dbName,
            final String collName,
            final Object documentId,
            final DBObject newContent,
            final String requestEtag,
            final boolean patching,
            final boolean checkEtag) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        // genereate new etag
        ObjectId newEtag = new ObjectId();

        final DBObject content = DAOUtils.validContent(newContent);

        content.put("_etag", newEtag);

        //TODO remove this after migration to mongodb driver 3.2 completes
        Document dcontent = new Document(content.toMap());

        OperationResult updateResult = DAOUtils.updateDocument(mcoll, documentId, dcontent, !patching);

        Document oldDocument = updateResult.getOldData();

        if (patching) {
            if (oldDocument == null) {
                return new OperationResult(HttpStatus.SC_NOT_FOUND);
            } else if (checkEtag) {
                // check the old etag (in case restore the old document version)
                return optimisticCheckEtag(mcoll, oldDocument, newEtag,
                        requestEtag, HttpStatus.SC_OK, false);
            } else {
                Document newDocument = mcoll.find(eq("_id", documentId)).first();

                return new OperationResult(HttpStatus.SC_OK, newEtag, oldDocument, newDocument);
            }
        } else if (oldDocument != null && checkEtag) { // upsertDocument
            // check the old etag (in case restore the old document)
            return optimisticCheckEtag(mcoll, oldDocument, newEtag,
                    requestEtag, HttpStatus.SC_OK, false);
        } else if (oldDocument != null) {  // insert
            Document newDocument = mcoll.find(eq("_id", documentId)).first();

            return new OperationResult(HttpStatus.SC_OK, newEtag, oldDocument, newDocument);
        } else {
            Document newDocument = mcoll.find(eq("_id", documentId)).first();

            return new OperationResult(HttpStatus.SC_CREATED, newEtag, null, newDocument);
        }
    }

    /**
     * @param dbName
     * @param collName
     * @param newContent
     * @param requestEtag
     * @param checkEtag
     * @return
     */
    @Override
    public OperationResult upsertDocumentPost(
            final String dbName,
            final String collName,
            final DBObject newContent,
            final String requestEtag,
            final boolean checkEtag) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        ObjectId newEtag = new ObjectId();

        final DBObject content = (newContent == null) ? new BasicDBObject() : newContent;

        content.put("_etag", newEtag);

        //TODO remove this after migration to mongodb driver 3.2 completes
        Document dcontent = new Document(content.toMap());

        Object documentId;

        if (dcontent.containsKey("_id")) {
            documentId = dcontent.get("_id");
        } else {
            documentId = Optional.empty(); // key _id is not present
        }
        // new document since the id is missing ()
        OperationResult updateResult = DAOUtils.updateDocument(
                mcoll,
                documentId,
                dcontent,
                true);

        Document oldDocument = updateResult.getOldData();
        Document newDocument = updateResult.getNewData();

        if (oldDocument == null) {
            return new OperationResult(HttpStatus.SC_CREATED, newEtag, null, newDocument);
        } else if (checkEtag) {  // upsertDocument
            // check the old etag (in case restore the old document version)
            return optimisticCheckEtag(mcoll, oldDocument, newEtag,
                    requestEtag, HttpStatus.SC_OK, false);
        } else {
            return new OperationResult(HttpStatus.SC_OK, newEtag, oldDocument, newDocument);
        }
    }

    /**
     * @param dbName
     * @param collName
     * @param documents
     * @return
     */
    @Override
    public BulkOperationResult bulkUpsertDocumentsPost(
            final String dbName,
            final String collName,
            final BasicDBList documents) {
        Objects.requireNonNull(documents);

        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        ObjectId newEtag = new ObjectId();

        //TODO remove this after migration to mongodb driver 3.2 completes
        List<Document> _documents = Lists.newArrayList();

        documents
                .stream()
                .forEachOrdered(document -> {
                    Document _document = new Document(((BasicDBObject) document).toMap());

                    _document.put("_etag", newEtag);

                    _documents.add(_document);
                });

        return DAOUtils.bulkUpsertDocuments(mcoll, _documents);
    }

    /**
     * @param dbName
     * @param collName
     * @param documentId
     * @param requestEtag
     * @param checkEtag
     * @return
     */
    @Override
    public OperationResult deleteDocument(
            final String dbName,
            final String collName,
            final Object documentId,
            final String requestEtag,
            final boolean checkEtag
    ) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        Document oldDocument = mcoll.findOneAndDelete(eq("_id", documentId));

        if (oldDocument == null) {
            return new OperationResult(HttpStatus.SC_NOT_FOUND);
        } else if (checkEtag) {
            // check the old etag (in case restore the old document version)
            return optimisticCheckEtag(mcoll, oldDocument, null,
                    requestEtag, HttpStatus.SC_NO_CONTENT, true);
        } else {
            return new OperationResult(HttpStatus.SC_NO_CONTENT);
        }
    }

    @Override
    public BulkOperationResult bulkDeleteDocuments(String dbName, String collName, Document filter) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        List<WriteModel<Document>> deletes = new ArrayList<>();

        deletes.add(new DeleteManyModel(filter));

        BulkWriteResult result = mcoll.bulkWrite(deletes);

        return new BulkOperationResult(HttpStatus.SC_OK, null, result);
    }

    @Override
    public BulkOperationResult bulkPatchDocuments(String dbName, String collName, Document filter, Document data) {
        MongoDatabase mdb = client.getDatabase(dbName);
        MongoCollection<Document> mcoll = mdb.getCollection(collName);

        List<WriteModel<Document>> patches = new ArrayList<>();

        patches.add(new UpdateManyModel(
                filter,
                DAOUtils.getUpdateDocument(data),
                DAOUtils.U_NOT_UPSERT_OPS));

        BulkWriteResult result = mcoll.bulkWrite(patches);

        return new BulkOperationResult(HttpStatus.SC_OK, null, result);
    }

    private OperationResult optimisticCheckEtag(
            final MongoCollection<Document> coll,
            final Document oldDocument,
            final Object newEtag,
            final String requestEtag,
            final int httpStatusIfOk,
            final boolean deleting
    ) {

        Object oldEtag = oldDocument.get("_etag");

        if (oldEtag != null && requestEtag == null) {
            // oopps, we need to restore old document
            // they call it optimistic lock strategy
            if (deleting) {
                DAOUtils.updateDocument(coll, oldDocument.get("_id"), oldDocument, true);
            } else {
                DAOUtils.restoreDocument(coll, oldDocument.get("_id"), oldDocument, newEtag);
            }

            return new OperationResult(HttpStatus.SC_CONFLICT, oldEtag, oldDocument, null);
        }

        String _oldEtag;

        if (oldEtag != null) {
            _oldEtag = oldEtag.toString();
        } else {
            _oldEtag = null;
        }

        if (Objects.equals(requestEtag, _oldEtag)) {
            Document newDocument = coll.find(eq("_id", oldDocument.get("_id"))).first();

            return new OperationResult(httpStatusIfOk, newEtag, oldDocument, newDocument);
        } else {
            // oopps, we need to restore old document
            // they call it optimistic lock strategy
            if (deleting) {
                DAOUtils.updateDocument(coll, oldDocument.get("_id"), oldDocument, true);
            } else {
                DAOUtils.restoreDocument(coll, oldDocument.get("_id"), oldDocument, newEtag);
            }

            return new OperationResult(HttpStatus.SC_PRECONDITION_FAILED, oldEtag, oldDocument, null);
        }
    }
}
