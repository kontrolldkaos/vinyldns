/*
 * Copyright 2018 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package vinyldns.api.domain.zone

import java.util.UUID

import org.joda.time.DateTime
import scalaz.Scalaz._
import scalaz._
import vinyldns.api.domain.DomainValidations._
import vinyldns.api.domain.{DomainValidationError, zone}
import vinyldns.api.domain.ValidationImprovements._
import vinyldns.core.crypto.{Crypto, CryptoAlgebra}

object ZoneStatus extends Enumeration {
  type ZoneStatus = Value
  val Active, Deleted, PendingUpdate, PendingDelete, Syncing = Value
}

import vinyldns.api.domain.zone.ZoneStatus._

case class Zone(
    name: String,
    email: String,
    status: ZoneStatus = ZoneStatus.Active,
    created: DateTime = DateTime.now(),
    updated: Option[DateTime] = None,
    id: String = UUID.randomUUID().toString,
    connection: Option[ZoneConnection] = None,
    transferConnection: Option[ZoneConnection] = None,
    account: String = "system",
    shared: Boolean = false,
    acl: ZoneACL = ZoneACL(),
    adminGroupId: String = "system",
    latestSync: Option[DateTime] = None) {
  val isIPv4: Boolean = name.endsWith("in-addr.arpa.")
  val isIPv6: Boolean = name.endsWith("ip6.arpa.")
  val isReverse: Boolean = isIPv4 || isIPv6

  def addACLRule(rule: ACLRule): Zone =
    this.copy(acl = acl.addRule(rule))

  def deleteACLRule(rule: ACLRule): Zone =
    this.copy(acl = acl.deleteRule(rule))

  override def toString: String = {
    val sb = new StringBuilder
    sb.append("Zone: [")
    sb.append("id=\"").append(id).append("\"; ")
    sb.append("name=\"").append(name).append("\"; ")
    sb.append("account=\"").append(account).append("\"; ")
    sb.append("adminGroupId=\"").append(adminGroupId).append("\"; ")
    sb.append("status=\"").append(status.toString).append("\"; ")
    sb.append("shared=\"").append(shared.toString).append("\"; ")
    sb.append("connection=\"").append(connection.toString).append("\"; ")
    sb.append("transferConnection=\"").append(transferConnection.toString).append("\"; ")
    sb.append("reverse=\"").append(isReverse.toString).append("\"; ")
    sb.append("]")
    sb.toString
  }
}

object Zone {
  val ZONE_MIN_LENGTH = 2 // Smaller of valid host name or IP address
  val ZONE_MAX_LENGTH = 255
  def build(
      name: String,
      email: String,
      adminGroupId: String,
      connection: Option[ZoneConnection],
      transfer: Option[ZoneConnection],
      zoneAcl: Option[ZoneACL]): ValidationNel[DomainValidationError, Zone] =
    (validateZoneName(name)
      |@| validateEmail(email)
      |@| adminGroupId.successNel
      |@| validateZoneConnection(connection)
      |@| validateZoneConnection(transfer)
      |@| validateZoneAcl(zoneAcl)) { (nm, em, ag, cn, tr, za) =>
      Zone(
        name = nm,
        email = em,
        adminGroupId = ag,
        connection = cn,
        transferConnection = tr,
        acl = za.getOrElse(ZoneACL())
      )
    }

  def validateZoneName(name: String): ValidationNel[DomainValidationError, String] =
    (validateStringLength(name, Some(ZONE_MIN_LENGTH), ZONE_MAX_LENGTH) +++ validateTrailingDot(
      name)).map(_ => name)

  def validateZoneConnection(connection: Option[ZoneConnection])
    : ValidationNel[DomainValidationError, Option[ZoneConnection]] =
    validateIfDefined(connection) { c =>
      ZoneConnection.build(c.name, c.keyName, c.key, c.primaryServer)
    }

  def validateZoneAcl(acl: Option[ZoneACL]): ValidationNel[DomainValidationError, Option[ZoneACL]] =
    validateIfDefined(acl) { acl =>
      ZoneACL.build(acl.rules)
    }
}

case class ZoneACL(rules: Set[ACLRule] = Set.empty) {

  def addRule(newRule: ACLRule): ZoneACL = copy(rules = rules + newRule)

  def deleteRule(rule: ACLRule): ZoneACL = copy(rules = rules - rule)
}

object ZoneACL {
  def build(rules: Set[ACLRule]): ValidationNel[DomainValidationError, ZoneACL] =
    rules.toList
      .traverseU(
        r =>
          ACLRule
            .build(r.accessLevel, r.description, r.userId, r.groupId, r.recordMask, r.recordTypes))
      .map(x => zone.ZoneACL(x.toSet[ACLRule]))
}

case class ZoneConnection(name: String, keyName: String, key: String, primaryServer: String) {

  def encrypted(crypto: CryptoAlgebra = Crypto.instance): ZoneConnection =
    copy(key = crypto.encrypt(key))

  def decrypted(crypto: CryptoAlgebra = Crypto.instance): ZoneConnection =
    copy(key = crypto.decrypt(key))
}

object ZoneConnection {

  final val ZONE_CONNECTION_MIN = 1
  final val ZONE_CONNECTION_MAX = 255
  def build(
      name: String,
      keyName: String,
      key: String,
      primaryServer: String): ValidationNel[DomainValidationError, ZoneConnection] =
    (validateStringLength(name, Some(ZONE_CONNECTION_MIN), ZONE_CONNECTION_MAX)
      |@| keyName.successNel
      |@| key.successNel
      |@| validateHostServer(primaryServer))(ZoneConnection.apply)

  def validateHostServer(host: String): ValidationNel[DomainValidationError, String] = {
    val splitHosts = host.split(":") // First part is host name or IPv4

    val checkDomainName = validateHostName(splitHosts(0))
    val checkIpv4 = validateIpv4Address(splitHosts(0))
    val checkPort = if (splitHosts.length == 1) "".successNel else validatePort(splitHosts(1))

    /*
     This is a little hard to follow:

     If it is either a valid domain name OR valid IP address and the port is valid, then return success.
     Otherwise, return any failures. If neither the domain name or IP address are valid, both failures will be returned.

     Note: We use the `.map(_ => ...)` convention here to return the original string upon success since the +++ operator
     appends Successes and Failures if both are of the same type.
     */
    (checkDomainName.findSuccess(checkIpv4) +++ checkPort).map(_ => host)
  }
}
