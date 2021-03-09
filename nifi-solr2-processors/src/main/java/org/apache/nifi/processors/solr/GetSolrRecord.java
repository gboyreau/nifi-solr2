/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.solr;

import static org.apache.nifi.processors.solr.SolrUtils.BASIC_PASSWORD;
import static org.apache.nifi.processors.solr.SolrUtils.BASIC_USERNAME;
import static org.apache.nifi.processors.solr.SolrUtils.COLLECTION;
import static org.apache.nifi.processors.solr.SolrUtils.KERBEROS_CREDENTIALS_SERVICE;
import static org.apache.nifi.processors.solr.SolrUtils.SOLR_CONNECTION_TIMEOUT;
import static org.apache.nifi.processors.solr.SolrUtils.SOLR_LOCATION;
import static org.apache.nifi.processors.solr.SolrUtils.SOLR_MAX_CONNECTIONS;
import static org.apache.nifi.processors.solr.SolrUtils.SOLR_MAX_CONNECTIONS_PER_HOST;
import static org.apache.nifi.processors.solr.SolrUtils.SOLR_SOCKET_TIMEOUT;
import static org.apache.nifi.processors.solr.SolrUtils.SOLR_TYPE;
import static org.apache.nifi.processors.solr.SolrUtils.SSL_CONTEXT_SERVICE;
import static org.apache.nifi.processors.solr.SolrUtils.ZK_CLIENT_TIMEOUT;
import static org.apache.nifi.processors.solr.SolrUtils.ZK_CONNECTION_TIMEOUT;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.nifi.annotation.behavior.ReadsAttribute;
import org.apache.nifi.annotation.behavior.ReadsAttributes;
import org.apache.nifi.annotation.behavior.WritesAttribute;
import org.apache.nifi.annotation.behavior.WritesAttributes;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.SeeAlso;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.annotation.lifecycle.OnScheduled;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.flowfile.attributes.FragmentAttributes;
import org.apache.nifi.logging.ComponentLog;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.schema.access.SchemaNotFoundException;
import org.apache.nifi.serialization.RecordSetWriter;
import org.apache.nifi.serialization.RecordSetWriterFactory;
import org.apache.nifi.serialization.record.RecordSchema;
import org.apache.nifi.serialization.record.RecordSet;
import org.apache.nifi.util.StringUtils;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrQuery.ORDER;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.request.QueryRequest;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.params.CursorMarkParams;

@Tags({"solr", "get", "fetch", "record", "json"})
@CapabilityDescription("A record-based version of GetSolr that uses the Record writers to write the Solr result set.")
@SeeAlso({})
@ReadsAttributes({@ReadsAttribute(attribute="", description="")})
@WritesAttributes({@WritesAttribute(attribute="", description="")})
public class GetSolrRecord extends SolrProcessor {
    public static final String FRAGMENT_ID = FragmentAttributes.FRAGMENT_ID.key();
    public static final String FRAGMENT_INDEX = FragmentAttributes.FRAGMENT_INDEX.key();
    public static final String FRAGMENT_COUNT = FragmentAttributes.FRAGMENT_COUNT.key();

	public static final PropertyDescriptor WRITER_FACTORY = new PropertyDescriptor.Builder()
			.name("get-mongo-record-writer-factory")
			.displayName("Record Writer")
			.description("The record writer to use to write the result sets.")
			.identifiesControllerService(RecordSetWriterFactory.class)
			.required(true)
			.build();
	public static final PropertyDescriptor SCHEMA_NAME = new PropertyDescriptor.Builder()
			.name("mongodb-schema-name")
			.displayName("Schema Name")
			.description("The name of the schema in the configured schema registry to use for the query results.")
			.expressionLanguageSupported(ExpressionLanguageScope.FLOWFILE_ATTRIBUTES)
			.addValidator(StandardValidators.NON_BLANK_VALIDATOR)
			.defaultValue("${schema.name}")
			.required(true)
			.build();
	public static final PropertyDescriptor SOLR_QUERY = new PropertyDescriptor
			.Builder().name("Solr Query")
			.displayName("Solr Query")
			.description("A query to execute against Solr")
			.required(false)
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.build();
	public static final PropertyDescriptor SOLR_FILTER = new PropertyDescriptor
			.Builder().name("Solr Filter")
			.displayName("Solr Filter")
			.description("A filter to execute against Solr")
			.required(false)
			.addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
			.build();
	public static final PropertyDescriptor BATCH_SIZE = new PropertyDescriptor
			.Builder().name("Batch Size")
			.displayName("Batch Size")
			.description("Number of rows per Solr query")
			.required(true)
			.addValidator(StandardValidators.INTEGER_VALIDATOR)
			.defaultValue("100")
			.build();

	public static final Relationship REL_SUCCESS = new Relationship.Builder()
			.name("success")
			.description("The results of querying Solr")
			.build();
	public static final Relationship REL_FAILURE = new Relationship.Builder()
			.name("failure")
			.description("All input FlowFiles that are part of a failed query execution go here.")
			.build();

    private final static Set<Relationship> relationships;
    private final static List<PropertyDescriptor> propertyDescriptors;

    static {
        List<PropertyDescriptor> _propertyDescriptors = new ArrayList<>();
        _propertyDescriptors.add(SOLR_TYPE);
        _propertyDescriptors.add(SOLR_LOCATION);
        _propertyDescriptors.add(COLLECTION);
        _propertyDescriptors.add(WRITER_FACTORY);
        _propertyDescriptors.add(SOLR_QUERY);
        _propertyDescriptors.add(SOLR_FILTER);
        _propertyDescriptors.add(BATCH_SIZE);
        _propertyDescriptors.add(KERBEROS_CREDENTIALS_SERVICE);
        _propertyDescriptors.add(BASIC_USERNAME);
        _propertyDescriptors.add(BASIC_PASSWORD);
        _propertyDescriptors.add(SSL_CONTEXT_SERVICE);
        _propertyDescriptors.add(SOLR_SOCKET_TIMEOUT);
        _propertyDescriptors.add(SOLR_CONNECTION_TIMEOUT);
        _propertyDescriptors.add(SOLR_MAX_CONNECTIONS);
        _propertyDescriptors.add(SOLR_MAX_CONNECTIONS_PER_HOST);
        _propertyDescriptors.add(ZK_CLIENT_TIMEOUT);
        _propertyDescriptors.add(ZK_CONNECTION_TIMEOUT);
        propertyDescriptors = Collections.unmodifiableList(_propertyDescriptors);

        final Set<Relationship> _relationships = new HashSet<>();
        _relationships.add(REL_SUCCESS);
        _relationships.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(_relationships);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    public final List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }
    
    private volatile RecordSetWriterFactory writerFactory;
    @OnScheduled
    public void onEnabled(ProcessContext context) {
        writerFactory = context.getProperty(WRITER_FACTORY).asControllerService(RecordSetWriterFactory.class);
    }

    private String getFieldNameOfUniqueKey() {
        final SolrQuery solrQuery = new SolrQuery();
        try {
            solrQuery.setRequestHandler("/schema/uniquekey");
            final QueryRequest req = new QueryRequest(solrQuery);
            if (isBasicAuthEnabled()) {
                req.setBasicAuthCredentials(getUsername(), getPassword());
            }

            return(req.process(getSolrClient()).getResponse().get("uniqueKey").toString());
        } catch (SolrServerException | IOException e) {
            getLogger().error("Solr query to retrieve uniqueKey-field failed due to {}", new Object[]{solrQuery.toString(), e}, e);
            throw new ProcessException(e);
        }
    }

    @Override
	protected void doOnTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile input = null;

        if (context.hasIncomingConnection()) {
            input = session.get();
            if (input == null && context.hasNonLoopConnection()) {
                return;
            }
        }

        int fragmentIndex = 0;
        final String fragmentId = UUID.randomUUID().toString();

        final String schemaName = context.getProperty(SCHEMA_NAME).evaluateAttributeExpressions(input).getValue();
        final SolrQuery solrQuery = new SolrQuery();
        final String id_field = getFieldNameOfUniqueKey();
        
        final Integer batchSize = context.getProperty(BATCH_SIZE).asInteger();
        String query = context.getProperty(SOLR_QUERY).evaluateAttributeExpressions(input).getValue();
        final String filter = context.getProperty(SOLR_FILTER).evaluateAttributeExpressions(input).getValue();

        if (StringUtils.isBlank(query)) {
            query = "*:*";
        }
        solrQuery.setQuery(query);
        if (!StringUtils.isBlank(filter) && !filter.equals("*:*")) {
            solrQuery.addFilterQuery(filter);
        }
        
        solrQuery.addSort(id_field, ORDER.asc);
        solrQuery.setRows(batchSize);
        solrQuery.setStart(0);
        final FlowFile inputPtr = input;
        try {
            onScheduled(context);
            Map<String, String> attrs = inputPtr != null ? inputPtr.getAttributes() : new HashMap<String, String>(){{
                put("schema.name", schemaName);
            }};
            RecordSchema schema = writerFactory.getSchema(attrs, null);

            String cursorMark = CursorMarkParams.CURSOR_MARK_START;
            solrQuery.setParam(CursorMarkParams.CURSOR_MARK_PARAM,"");
            while( !cursorMark.equals(solrQuery.getParams(CursorMarkParams.CURSOR_MARK_PARAM)[0]) ) {
                final QueryRequest req = new QueryRequest(solrQuery);
                if (isBasicAuthEnabled()) {
                    req.setBasicAuthCredentials(getUsername(), getPassword());
                }
                solrQuery.setParam(CursorMarkParams.CURSOR_MARK_PARAM,cursorMark);
                getLogger().debug(solrQuery.toQueryString());
                final QueryResponse response = req.process(getSolrClient());
                final SolrDocumentList documentList = response.getResults();
                
                if( documentList.size() > 0 ) {
                    final long fragmentCount = Math.round( Math.ceil(documentList.getNumFound() / batchSize) );
                	final RecordSet recordSet = SolrUtils.solrDocumentsToRecordSet(documentList, schema);
                	final StringBuffer mimeType = new StringBuffer();
                	FlowFile output = input != null ? session.create(input) : session.create();
                	output = session.write(output, new OutputStreamCallback() {
                        @Override
                        public void process(final OutputStream out) throws IOException {
                            try {
                                final RecordSetWriter writer = writerFactory.createWriter(getLogger(), schema, out);
                                writer.write(recordSet);
                                writer.flush();
                                writer.close();
                                mimeType.append(writer.getMimeType());
                            } catch (SchemaNotFoundException e) {
                                throw new ProcessException("Could not parse Solr response", e);
                            }
                        }
                	});
                    final Map<String, String> attributesToAdd = new HashMap<>();
                    attributesToAdd.put(CoreAttributes.MIME_TYPE.key(), mimeType.toString());
                    attributesToAdd.put(FRAGMENT_ID, fragmentId);
                    attributesToAdd.put(FRAGMENT_INDEX, String.valueOf(fragmentIndex));
                    attributesToAdd.put(FRAGMENT_COUNT, String.valueOf(fragmentCount));
                    output = session.putAllAttributes(output, attributesToAdd);

                    session.transfer(output, REL_SUCCESS);
                }
                fragmentIndex++;
                cursorMark = response.getNextCursorMark();
            }
//            session.getProvenanceReporter().fetch(output, getURI(context));
        } catch (Exception ex) {
            ex.printStackTrace();
            getLogger().error("Error writing record set from Solr query.", ex);
            if (input != null) {
                session.transfer(input, REL_FAILURE);
            }
        }

	}
}
