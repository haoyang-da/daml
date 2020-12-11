// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package transaction

import com.daml.lf.data.ImmArray
import com.daml.lf.language.LanguageVersion
import com.daml.lf.value.{Value, ValueVersion, ValueVersions}

import scala.collection.immutable.HashMap

final case class TransactionVersion(protoValue: String)

/**
  * Currently supported versions of the DAML-LF transaction specification.
  */
object TransactionVersions
    extends LfVersions(versionsAscending = VersionTimeline.ascendingVersions[TransactionVersion])(
      _.protoValue,
    ) {

  import VersionTimeline._
  import VersionTimeline.Implicits._

  val minVersion = TransactionVersion("10")
  private[transaction] val minChoiceObservers = TransactionVersion("dev")
  private[transaction] val minNodeVersion = TransactionVersion("dev")

  // Older versions are deprecated https://github.com/digital-asset/daml/issues/5220
  private[lf] val StableOutputVersions: VersionRange[TransactionVersion] =
    VersionRange(TransactionVersion("10"), TransactionVersion("10"))

  private[lf] val DevOutputVersions: VersionRange[TransactionVersion] =
    StableOutputVersions.copy(max = acceptedVersions.last)

  private[lf] val Empty: VersionRange[TransactionVersion] =
    VersionRange(acceptedVersions.last, acceptedVersions.head)

  private[lf] def assignValueVersion(nodeVersion: TransactionVersion): ValueVersion =
    latestWhenAllPresent(ValueVersions.acceptedVersions.head, nodeVersion)

  private[lf] def assignNodeVersion(langVersion: LanguageVersion): TransactionVersion =
    VersionTimeline.latestWhenAllPresent(TransactionVersions.minVersion, langVersion)

  private[lf] def asVersionedTransaction(
      roots: ImmArray[NodeId],
      nodes: HashMap[NodeId, Node.GenNode[NodeId, Value.ContractId]],
  ): VersionedTransaction[NodeId, Value.ContractId] = {

    val txVersion = roots.iterator.foldLeft(TransactionVersions.minVersion)((acc, nodeId) =>
      VersionTimeline.maxVersion(acc, nodes(nodeId).version))

    VersionedTransaction(txVersion, nodes, roots)
  }

}
