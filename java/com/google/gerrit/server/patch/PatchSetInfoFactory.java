// Copyright (C) 2009 The Android Open Source Project
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

package com.google.gerrit.server.patch;

import com.google.gerrit.exceptions.StorageException;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.client.PatchSetInfo;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.UserIdentity;
import com.google.gerrit.server.PatchSetUtil;
import com.google.gerrit.server.account.Emails;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.io.IOException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/** Factory class creating PatchSetInfo from meta-data found in Git repository. */
@Singleton
public class PatchSetInfoFactory {
  private final GitRepositoryManager repoManager;
  private final PatchSetUtil psUtil;
  private final Emails emails;

  @Inject
  public PatchSetInfoFactory(GitRepositoryManager repoManager, PatchSetUtil psUtil, Emails emails) {
    this.repoManager = repoManager;
    this.psUtil = psUtil;
    this.emails = emails;
  }

  public PatchSetInfo get(RevWalk rw, RevCommit src, PatchSet.Id psi) throws IOException {
    rw.parseBody(src);
    PatchSetInfo info = new PatchSetInfo(psi);
    info.setSubject(src.getShortMessage());
    info.setMessage(src.getFullMessage());
    info.setAuthor(toUserIdentity(src.getAuthorIdent()));
    info.setCommitter(toUserIdentity(src.getCommitterIdent()));
    info.setCommitId(src);
    return info;
  }

  public PatchSetInfo get(ChangeNotes notes, PatchSet.Id psId)
      throws PatchSetInfoNotAvailableException {
    try {
      PatchSet patchSet = psUtil.get(notes, psId);
      return get(notes.getProjectName(), patchSet);
    } catch (StorageException e) {
      throw new PatchSetInfoNotAvailableException(e);
    }
  }

  public PatchSetInfo get(Project.NameKey project, PatchSet patchSet)
      throws PatchSetInfoNotAvailableException {
    try (Repository repo = repoManager.openRepository(project);
        RevWalk rw = new RevWalk(repo)) {
      RevCommit src = rw.parseCommit(patchSet.commitId());
      PatchSetInfo info = get(rw, src, patchSet.id());
      info.setParents(toParentInfos(src.getParents(), rw));
      return info;
    } catch (IOException | StorageException e) {
      throw new PatchSetInfoNotAvailableException(e);
    }
  }

  // TODO: The same method exists in EventFactory, find a common place for it
  private UserIdentity toUserIdentity(PersonIdent who) throws IOException {
    final UserIdentity u = new UserIdentity();
    u.setName(who.getName());
    u.setEmail(who.getEmailAddress());
    u.setDate(new Timestamp(who.getWhen().getTime()));
    u.setTimeZone(who.getTimeZoneOffset());

    // If only one account has access to this email address, select it
    // as the identity of the user.
    //
    Set<Account.Id> a = emails.getAccountFor(u.getEmail());
    if (a.size() == 1) {
      u.setAccount(a.iterator().next());
    }

    return u;
  }

  private List<PatchSetInfo.ParentInfo> toParentInfos(RevCommit[] parents, RevWalk walk)
      throws IOException, MissingObjectException {
    List<PatchSetInfo.ParentInfo> pInfos = new ArrayList<>(parents.length);
    for (RevCommit parent : parents) {
      walk.parseBody(parent);
      String msg = parent.getShortMessage();
      pInfos.add(new PatchSetInfo.ParentInfo(parent, msg));
    }
    return pInfos;
  }
}
