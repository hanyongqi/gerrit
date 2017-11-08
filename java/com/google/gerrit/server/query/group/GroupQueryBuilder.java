// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.query.group;

import static com.google.common.collect.ImmutableList.toImmutableList;

import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.primitives.Ints;
import com.google.gerrit.common.data.GroupReference;
import com.google.gerrit.index.FieldDef;
import com.google.gerrit.index.query.LimitPredicate;
import com.google.gerrit.index.query.Predicate;
import com.google.gerrit.index.query.QueryBuilder;
import com.google.gerrit.index.query.QueryParseException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.AccountGroup;
import com.google.gerrit.server.account.AccountResolver;
import com.google.gerrit.server.account.GroupBackend;
import com.google.gerrit.server.account.GroupBackends;
import com.google.gerrit.server.account.GroupCache;
import com.google.gerrit.server.group.InternalGroup;
import com.google.gerrit.server.index.group.GroupField;
import com.google.gerrit.server.index.group.GroupIndex;
import com.google.gerrit.server.index.group.GroupIndexCollection;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.jgit.errors.ConfigInvalidException;

/** Parses a query string meant to be applied to group objects. */
public class GroupQueryBuilder extends QueryBuilder<InternalGroup> {
  public static final String FIELD_UUID = "uuid";
  public static final String FIELD_DESCRIPTION = "description";
  public static final String FIELD_INNAME = "inname";
  public static final String FIELD_NAME = "name";
  @Deprecated public static final String FIELD_OWNER = "owner";
  public static final String FIELD_LIMIT = "limit";

  private static final QueryBuilder.Definition<InternalGroup, GroupQueryBuilder> mydef =
      new QueryBuilder.Definition<>(GroupQueryBuilder.class);

  public static class Arguments {
    final GroupIndex groupIndex;
    final GroupCache groupCache;
    final GroupBackend groupBackend;
    final AccountResolver accountResolver;

    @Inject
    Arguments(
        GroupIndexCollection groupIndexCollection,
        GroupCache groupCache,
        GroupBackend groupBackend,
        AccountResolver accountResolver) {
      this.groupIndex = groupIndexCollection.getSearchIndex();
      this.groupCache = groupCache;
      this.groupBackend = groupBackend;
      this.accountResolver = accountResolver;
    }
  }

  private final Arguments args;

  @Inject
  GroupQueryBuilder(Arguments args) {
    super(mydef);
    this.args = args;
  }

  @Operator
  public Predicate<InternalGroup> uuid(String uuid) {
    return GroupPredicates.uuid(new AccountGroup.UUID(uuid));
  }

  @Operator
  public Predicate<InternalGroup> description(String description) throws QueryParseException {
    if (Strings.isNullOrEmpty(description)) {
      throw error("description operator requires a value");
    }

    return GroupPredicates.description(description);
  }

  @Operator
  public Predicate<InternalGroup> inname(String namePart) {
    if (namePart.isEmpty()) {
      return name(namePart);
    }
    return GroupPredicates.inname(namePart);
  }

  @Operator
  public Predicate<InternalGroup> name(String name) {
    return GroupPredicates.name(name);
  }

  @Operator
  public Predicate<InternalGroup> is(String value) throws QueryParseException {
    if ("visibletoall".equalsIgnoreCase(value)) {
      return GroupPredicates.isVisibleToAll();
    }
    throw error("Invalid query");
  }

  @Override
  protected Predicate<InternalGroup> defaultField(String query) throws QueryParseException {
    // Adapt the capacity of this list when adding more default predicates.
    List<Predicate<InternalGroup>> preds = Lists.newArrayListWithCapacity(5);
    preds.add(uuid(query));
    preds.add(name(query));
    preds.add(inname(query));
    if (!Strings.isNullOrEmpty(query)) {
      preds.add(description(query));
    }
    return Predicate.or(preds);
  }

  @Operator
  public Predicate<InternalGroup> member(String query)
      throws QueryParseException, OrmException, ConfigInvalidException, IOException {
    if (isFieldAbsentFromIndex(GroupField.MEMBER)) {
      throw getExceptionForUnsupportedOperator("member");
    }

    Set<Account.Id> accounts = parseAccount(query);
    List<Predicate<InternalGroup>> predicates =
        accounts.stream().map(GroupPredicates::member).collect(toImmutableList());
    return Predicate.or(predicates);
  }

  @Operator
  public Predicate<InternalGroup> subgroup(String query) throws QueryParseException {
    if (isFieldAbsentFromIndex(GroupField.SUBGROUP)) {
      throw getExceptionForUnsupportedOperator("subgroup");
    }

    AccountGroup.UUID groupUuid = parseGroup(query);
    return GroupPredicates.subgroup(groupUuid);
  }

  @Operator
  public Predicate<InternalGroup> limit(String query) throws QueryParseException {
    Integer limit = Ints.tryParse(query);
    if (limit == null) {
      throw error("Invalid limit: " + query);
    }
    return new LimitPredicate<>(FIELD_LIMIT, limit);
  }

  private boolean isFieldAbsentFromIndex(FieldDef<InternalGroup, ?> field) {
    return !args.groupIndex.getSchema().hasField(field);
  }

  private static QueryParseException getExceptionForUnsupportedOperator(String operatorName) {
    return new QueryParseException(
        String.format("'%s' operator is not supported by group index version", operatorName));
  }

  private Set<Account.Id> parseAccount(String nameOrEmail)
      throws QueryParseException, OrmException, IOException, ConfigInvalidException {
    Set<Account.Id> foundAccounts = args.accountResolver.findAll(nameOrEmail);
    if (foundAccounts.isEmpty()) {
      throw error("User " + nameOrEmail + " not found");
    }
    return foundAccounts;
  }

  private AccountGroup.UUID parseGroup(String groupNameOrUuid) throws QueryParseException {
    Optional<InternalGroup> group = args.groupCache.get(new AccountGroup.UUID(groupNameOrUuid));
    if (group.isPresent()) {
      return group.get().getGroupUUID();
    }
    GroupReference groupReference =
        GroupBackends.findBestSuggestion(args.groupBackend, groupNameOrUuid);
    if (groupReference == null) {
      throw error("Group " + groupNameOrUuid + " not found");
    }
    return groupReference.getUUID();
  }
}
