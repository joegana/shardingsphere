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

package org.apache.shardingsphere.proxy.backend.text.metadata.rdl;

import lombok.RequiredArgsConstructor;
import org.apache.shardingsphere.distsql.parser.statement.rdl.CreateDataSourcesStatement;
import org.apache.shardingsphere.distsql.parser.statement.rdl.CreateShardingRuleStatement;
import org.apache.shardingsphere.distsql.parser.statement.rdl.ShowShardingRuleStatement;
import org.apache.shardingsphere.governance.core.event.model.datasource.DataSourcePersistEvent;
import org.apache.shardingsphere.governance.core.event.model.rule.RuleConfigurationsPersistEvent;
import org.apache.shardingsphere.governance.core.event.model.schema.SchemaNamePersistEvent;
import org.apache.shardingsphere.infra.binder.statement.SQLStatementContext;
import org.apache.shardingsphere.infra.binder.statement.ddl.CreateDatabaseStatementContext;
import org.apache.shardingsphere.infra.binder.statement.ddl.DropDatabaseStatementContext;
import org.apache.shardingsphere.infra.binder.statement.rdl.CreateDataSourcesStatementContext;
import org.apache.shardingsphere.infra.binder.statement.rdl.CreateShardingRuleStatementContext;
import org.apache.shardingsphere.infra.binder.statement.rdl.ShowShardingRuleStatementContext;
import org.apache.shardingsphere.infra.config.RuleConfiguration;
import org.apache.shardingsphere.infra.config.datasource.DataSourceConfiguration;
import org.apache.shardingsphere.infra.context.metadata.impl.StandardMetaDataContexts;
import org.apache.shardingsphere.infra.database.type.DatabaseType;
import org.apache.shardingsphere.infra.eventbus.ShardingSphereEventBus;
import org.apache.shardingsphere.infra.yaml.config.YamlRuleConfiguration;
import org.apache.shardingsphere.infra.yaml.engine.YamlEngine;
import org.apache.shardingsphere.infra.yaml.swapper.YamlRuleConfigurationSwapperEngine;
import org.apache.shardingsphere.proxy.backend.communication.jdbc.connection.BackendConnection;
import org.apache.shardingsphere.proxy.backend.context.ProxyContext;
import org.apache.shardingsphere.proxy.backend.exception.DBCreateExistsException;
import org.apache.shardingsphere.proxy.backend.response.header.ResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.QueryResponseHeader;
import org.apache.shardingsphere.proxy.backend.response.header.query.impl.QueryHeader;
import org.apache.shardingsphere.proxy.backend.response.header.update.UpdateResponseHeader;
import org.apache.shardingsphere.proxy.backend.text.TextProtocolBackendHandler;
import org.apache.shardingsphere.proxy.config.util.DataSourceParameterConverter;
import org.apache.shardingsphere.proxy.config.yaml.YamlDataSourceParameter;
import org.apache.shardingsphere.proxy.converter.CreateDataSourcesStatementContextConverter;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.converter.CreateShardingRuleStatementContextConverter;
import org.apache.shardingsphere.sharding.yaml.config.YamlShardingRuleConfiguration;
import org.apache.shardingsphere.sql.parser.sql.common.statement.SQLStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.CreateDatabaseStatement;
import org.apache.shardingsphere.sql.parser.sql.common.statement.ddl.DropDatabaseStatement;

import java.sql.SQLException;
import java.sql.Types;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;

/**
 * Backend handler for RDL.
 */
@RequiredArgsConstructor
public final class RDLBackendHandler implements TextProtocolBackendHandler {
    
    private final BackendConnection backendConnection;
    
    private final SQLStatement sqlStatement;
    
    private Iterator<RuleConfiguration> ruleConfigData;
    
    @Override
    public ResponseHeader execute() throws SQLException {
        SQLStatementContext<?> context = getSQLStatementContext();
        if (!isRegistryCenterExisted() && !isQuery()) {
            throw new SQLException(String.format("No Registry center to execute `%s` SQL", context.getClass().getSimpleName()));
        }
        return getResponseHeader(context);
    }
    
    private ResponseHeader execute(final CreateDatabaseStatementContext context) {
        if (ProxyContext.getInstance().getAllSchemaNames().contains(context.getSqlStatement().getDatabaseName())) {
            throw new DBCreateExistsException(context.getSqlStatement().getDatabaseName());
        }
        // TODO Need to get the executed feedback from registry center for returning.
        ShardingSphereEventBus.getInstance().post(new SchemaNamePersistEvent(context.getSqlStatement().getDatabaseName(), false));
        return new UpdateResponseHeader(context.getSqlStatement());
    }
    
    private ResponseHeader execute(final DropDatabaseStatementContext context) {
        if (!ProxyContext.getInstance().getAllSchemaNames().contains(context.getSqlStatement().getDatabaseName())) {
            throw new DBCreateExistsException(context.getSqlStatement().getDatabaseName());
        }
        // TODO Need to get the executed feedback from registry center for returning.
        ShardingSphereEventBus.getInstance().post(new SchemaNamePersistEvent(context.getSqlStatement().getDatabaseName(), true));
        return new UpdateResponseHeader(context.getSqlStatement());
    }
    
    private ResponseHeader execute(final CreateDataSourcesStatementContext context) {
        Map<String, YamlDataSourceParameter> parameters = CreateDataSourcesStatementContextConverter.convert(context);
        Map<String, DataSourceConfiguration> dataSources = DataSourceParameterConverter.getDataSourceConfigurationMap(
                DataSourceParameterConverter.getDataSourceParameterMapFromYamlConfiguration(parameters));
        // TODO Need to get the executed feedback from registry center for returning.
        ShardingSphereEventBus.getInstance().post(new DataSourcePersistEvent(backendConnection.getSchemaName(), dataSources));
        return new UpdateResponseHeader(context.getSqlStatement());
    }
    
    private ResponseHeader execute(final CreateShardingRuleStatementContext context) {
        YamlShardingRuleConfiguration config = CreateShardingRuleStatementContextConverter.convert(context);
        Collection<RuleConfiguration> rules = new YamlRuleConfigurationSwapperEngine().swapToRuleConfigurations(Collections.singleton(config));
        // TODO Need to get the executed feedback from registry center for returning.
        ShardingSphereEventBus.getInstance().post(new RuleConfigurationsPersistEvent(backendConnection.getSchemaName(), rules));
        return new UpdateResponseHeader(context.getSqlStatement());
    }
    
    private ResponseHeader execute(final ShowShardingRuleStatementContext context) {
        String schemaName = null == context.getSqlStatement().getSchemaName() ? backendConnection.getSchemaName() : context.getSqlStatement().getSchemaName().getIdentifier().getValue();
        QueryHeader queryHeader = new QueryHeader(schemaName, "", "ShardingRule", "ShardingRule", Types.CHAR, "CHAR", 255, 0, false, false, false, false);
        Optional<RuleConfiguration> ruleConfig = 
                ProxyContext.getInstance().getMetaData(schemaName).getRuleMetaData().getConfigurations().stream().filter(each -> each instanceof ShardingRuleConfiguration).findAny();
        ruleConfig.ifPresent(shardingSphereRule -> ruleConfigData = Collections.singleton(shardingSphereRule).iterator());
        return new QueryResponseHeader(Collections.singletonList(queryHeader));
    }
    
    private SQLStatementContext<?> getSQLStatementContext() {
        DatabaseType databaseType = ProxyContext.getInstance().getMetaDataContexts().getMetaData(backendConnection.getSchemaName()).getResource().getDatabaseType();
        if (sqlStatement instanceof CreateDataSourcesStatement) {
            return new CreateDataSourcesStatementContext((CreateDataSourcesStatement) sqlStatement, databaseType);
        }
        if (sqlStatement instanceof CreateShardingRuleStatement) {
            return new CreateShardingRuleStatementContext((CreateShardingRuleStatement) sqlStatement);
        }
        if (sqlStatement instanceof CreateDatabaseStatement) {
            return new CreateDatabaseStatementContext((CreateDatabaseStatement) sqlStatement);
        }
        if (sqlStatement instanceof DropDatabaseStatement) {
            return new DropDatabaseStatementContext((DropDatabaseStatement) sqlStatement);
        }
        if (sqlStatement instanceof ShowShardingRuleStatement) {
            return new ShowShardingRuleStatementContext((ShowShardingRuleStatement) sqlStatement);
        }
        throw new UnsupportedOperationException(sqlStatement.getClass().getName());
    }
    
    private ResponseHeader getResponseHeader(final SQLStatementContext<?> context) {
        if (context instanceof CreateDatabaseStatementContext) {
            return execute((CreateDatabaseStatementContext) context);
        }
        if (context instanceof CreateDataSourcesStatementContext) {
            return execute((CreateDataSourcesStatementContext) context);
        }
        if (context instanceof DropDatabaseStatementContext) {
            return execute((DropDatabaseStatementContext) context);
        }
        if (context instanceof CreateShardingRuleStatementContext) {
            return execute((CreateShardingRuleStatementContext) context);
        }
        if (context instanceof ShowShardingRuleStatementContext) {
            return execute((ShowShardingRuleStatementContext) context);
        }
        throw new UnsupportedOperationException(context.getClass().getName());
    }
    
    private boolean isRegistryCenterExisted() {
        return !(ProxyContext.getInstance().getMetaDataContexts() instanceof StandardMetaDataContexts);
    }
    
    private boolean isQuery() {
        return sqlStatement instanceof ShowShardingRuleStatement;
    }
    
    @Override
    public boolean next() {
        return null != ruleConfigData && ruleConfigData.hasNext();
    }
    
    @Override
    public Collection<Object> getRowData() {
        RuleConfiguration ruleConfig = ruleConfigData.next();
        YamlRuleConfiguration yamlRuleConfig = new YamlRuleConfigurationSwapperEngine().swapToYamlConfigurations(Collections.singleton(ruleConfig)).iterator().next();
        return Collections.singleton(YamlEngine.marshal(yamlRuleConfig));
    }
}