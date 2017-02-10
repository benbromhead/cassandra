/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.auth;

import java.net.InetAddress;
import java.security.cert.Certificate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.config.SchemaConstants;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.cql3.statements.SelectStatement;
import org.apache.cassandra.exceptions.AuthenticationException;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.QueryState;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.mindrot.jbcrypt.BCrypt;

import static org.apache.cassandra.auth.CassandraRoleManager.consistencyForRole;

/**
 * PasswordAuthenticator is an IAuthenticator implementation
 * that keeps credentials (rolenames and bcrypt-hashed passwords)
 * internally in C* - in system_auth.roles CQL3 table.
 * Since 2.2, the management of roles (creation, modification,
 * querying etc is the responsibility of IRoleManager. Use of
 * PasswordAuthenticator requires the use of CassandraRoleManager
 * for storage and retrieval of encrypted passwords.
 */
public class PasswordAuthenticator implements IAuthenticator
{
    private static final Logger logger = LoggerFactory.getLogger(PasswordAuthenticator.class);
    private static final List<String> supportedMechanisms = Lists.newArrayList("PLAIN");

    // name of the hash column.
    private static final String SALTED_HASH = "salted_hash";

    // really this is a rolename now, but as it only matters for Thrift, we leave it for backwards compatibility
    public static final String USERNAME_KEY = "username";
    public static final String PASSWORD_KEY = "password";
    public static final byte[] BYTES = new byte[0];

    private SelectStatement authenticateStatement;

    public static final String LEGACY_CREDENTIALS_TABLE = "credentials";
    private SelectStatement legacyAuthenticateStatement;

    private CredentialsCache cache;

    // No anonymous access.
    public boolean requireAuthentication()
    {
        return true;
    }

    private AuthenticatedUser authenticate(String username, String password) throws AuthenticationException
    {
        try
        {
            String hash = cache.get(username);
            if (!BCrypt.checkpw(password, hash))
                throw new AuthenticationException(String.format("Provided username %s and/or password are incorrect", username));

            return new AuthenticatedUser(username);
        }
        catch (ExecutionException e)
        {
            throw new RuntimeException(e);
        }
    }

    private String queryHashedPassword(String username)
    {
        try
        {
            SelectStatement authenticationStatement = authenticationStatement();

            ResultMessage.Rows rows =
                authenticationStatement.execute(QueryState.forInternalCalls(),
                                                QueryOptions.forInternalCalls(consistencyForRole(username),
                                                                              Lists.newArrayList(ByteBufferUtil.bytes(username))),
                                                System.nanoTime());

            // If either a non-existent role name was supplied, or no credentials
            // were found for that role we don't want to cache the result so we throw
            // a specific, but unchecked, exception to keep LoadingCache happy.
            if (rows.result.isEmpty())
                throw new AuthenticationException(String.format("Provided username %s and/or password are incorrect", username));

            UntypedResultSet result = UntypedResultSet.create(rows.result);
            if (!result.one().has(SALTED_HASH))
                throw new AuthenticationException(String.format("Provided username %s and/or password are incorrect", username));

            return result.one().getString(SALTED_HASH);
        }
        catch (RequestExecutionException e)
        {
            throw new AuthenticationException(String.format("Error performing internal authentication of user %s : %s", username, e.getMessage()));
        }
    }

    /**
     * If the legacy users table exists try to verify credentials there. This is to handle the case
     * where the cluster is being upgraded and so is running with mixed versions of the authn tables
     */
    private SelectStatement authenticationStatement()
    {
        if (Schema.instance.getCFMetaData(SchemaConstants.AUTH_KEYSPACE_NAME, LEGACY_CREDENTIALS_TABLE) == null)
            return authenticateStatement;
        else
        {
            // the statement got prepared, we to try preparing it again.
            // If the credentials was initialised only after statement got prepared, re-prepare (CASSANDRA-12813).
            if (legacyAuthenticateStatement == null)
                prepareLegacyAuthenticateStatement();
            return legacyAuthenticateStatement;
        }
    }


    public Set<DataResource> protectedResources()
    {
        // Also protected by CassandraRoleManager, but the duplication doesn't hurt and is more explicit
        return ImmutableSet.of(DataResource.table(SchemaConstants.AUTH_KEYSPACE_NAME, AuthKeyspace.ROLES));
    }

    public void validateConfiguration() throws ConfigurationException
    {
    }

    public void setup()
    {
        String query = String.format("SELECT %s FROM %s.%s WHERE role = ?",
                                     SALTED_HASH,
                                     SchemaConstants.AUTH_KEYSPACE_NAME,
                                     AuthKeyspace.ROLES);
        authenticateStatement = prepare(query);

        if (Schema.instance.getCFMetaData(SchemaConstants.AUTH_KEYSPACE_NAME, LEGACY_CREDENTIALS_TABLE) != null)
            prepareLegacyAuthenticateStatement();

        cache = new CredentialsCache(this);
    }

    private void prepareLegacyAuthenticateStatement()
    {
        String query = String.format("SELECT %s from %s.%s WHERE username = ?",
                                     SALTED_HASH,
                                     SchemaConstants.AUTH_KEYSPACE_NAME,
                                     LEGACY_CREDENTIALS_TABLE);
        legacyAuthenticateStatement = prepare(query);
    }

    public AuthenticatedUser legacyAuthenticate(Map<String, String> credentials) throws AuthenticationException
    {
        String username = credentials.get(USERNAME_KEY);
        if (username == null)
            throw new AuthenticationException(String.format("Required key '%s' is missing", USERNAME_KEY));

        String password = credentials.get(PASSWORD_KEY);
        if (password == null)
            throw new AuthenticationException(String.format("Required key '%s' is missing for provided username %s", PASSWORD_KEY, username));

        return authenticate(username, password);
    }

    public List<String> getSupportedSaslMechanisms()
    {
        return supportedMechanisms;
    }

    public SaslNegotiator newSaslNegotiator(InetAddress clientAddress, Certificate[] certificates)
    {
        return new PlainTextCqlSaslNegotiator() {
            public AuthenticatedUser getAuthenticatedUser() throws AuthenticationException
            {
                logger.debug("Authenticating user with new auth mechanism");
                if (!complete)
                    throw new AuthenticationException("SASL negotiation not complete");
                return authenticate(username, password);
            }
        };
    }

    public SaslNegotiator newLegacySaslNegotiator(InetAddress clientAddress)
    {
        return new PlainTextCqlSaslNegotiator() {
            public byte[] evaluateResponse(byte[] clientResponse) throws AuthenticationException
            {
                logger.debug("Authenticating user with legacy auth mechanism");
                return evaluateResponseAfterNegotiation(clientResponse);
            }

            public AuthenticatedUser getAuthenticatedUser() throws AuthenticationException
            {
                if (!complete)
                    throw new AuthenticationException("SASL negotiation not complete");
                return authenticate(username, password);
            }
        };
    }

    private static SelectStatement prepare(String query)
    {
        return (SelectStatement) QueryProcessor.getStatement(query, ClientState.forInternalCalls()).statement;
    }

    private static class CredentialsCache extends AuthCache<String, String> implements CredentialsCacheMBean
    {
        private CredentialsCache(PasswordAuthenticator authenticator)
        {
            super("CredentialsCache",
                  DatabaseDescriptor::setCredentialsValidity,
                  DatabaseDescriptor::getCredentialsValidity,
                  DatabaseDescriptor::setCredentialsUpdateInterval,
                  DatabaseDescriptor::getCredentialsUpdateInterval,
                  DatabaseDescriptor::setCredentialsCacheMaxEntries,
                  DatabaseDescriptor::getCredentialsCacheMaxEntries,
                  authenticator::queryHashedPassword,
                  () -> true);
        }

        public void invalidateCredentials(String roleName)
        {
            invalidate(roleName);
        }
    }

    public static interface CredentialsCacheMBean extends AuthCacheMBean
    {
        public void invalidateCredentials(String roleName);
    }
}
