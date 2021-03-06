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

package vinyldns.api.domain

import com.aaronbedra.orchard.CIDR
import scalaz.Disjunction
import vinyldns.api.Interfaces._
import vinyldns.api.domain.record.RecordType
import vinyldns.api.domain.record.RecordType.RecordType
import vinyldns.api.domain.zone.{InvalidRequest, Zone}

import scala.util.Try

object ReverseZoneHelpers {

  def recordsetIsWithinCidrMask(mask: String, zone: Zone, recordName: String): Boolean =
    if (zone.isIPv4) {
      recordsetIsWithinCidrMaskIpv4(mask: String, zone: Zone, recordName: String)
    } else {
      val ipAddr = convertPTRtoIPv6(zone, recordName)
      Try(CIDR.valueOf(mask).contains(ipAddr)).getOrElse(false)
    }

  def ptrIsInZone(
      zone: Zone,
      recordName: String,
      recordType: RecordType): Disjunction[Throwable, Unit] =
    recordType match {
      case RecordType.PTR => {
        if (zone.isIPv4) {
          handleIpv4RecordValidation(zone: Zone, recordName)
        } else if (zone.isIPv6) {
          handleIpv6RecordValidation(zone: Zone, recordName)
        } else {
          InvalidRequest(
            s"RecordSet $recordName does not specify a valid IP address in zone ${zone.name}").left
        }
      }
      case _ => ().right
    }

  private[domain] def convertPTRtoIPv4(zone: Zone, recordName: String): String = {
    val zoneName = zone.name.split("in-addr.arpa.")(0)
    val zoneOctets = ipv4ReverseSplitByOctets(zoneName)
    val recordOctets = ipv4ReverseSplitByOctets(recordName)

    if (zone.name.contains("/")) {
      (zoneOctets.dropRight(1) ++ recordOctets).mkString(".")
    } else {
      (zoneOctets ++ recordOctets).mkString(".")
    }
  }

  private[domain] def convertPTRtoIPv6(zone: Zone, recordName: String): String = {
    val zoneName = zone.name.split("ip6.arpa.")(0)
    val zoneNameNibblesReversed = zoneName.split('.').reverse.toList
    val recordSetNibblesReversed = recordName.split('.').reverse.toList
    val allUnseparated = (zoneNameNibblesReversed ++ recordSetNibblesReversed).mkString("")
    allUnseparated.grouped(4).reduce(_ + ":" + _)
  }

  private def recordsetIsWithinCidrMaskIpv4(
      mask: String,
      zone: Zone,
      recordName: String): Boolean = {
    val recordIpAddr = convertPTRtoIPv4(zone, recordName)

    Try {
      // make sure mask contains 4 octets, expand if not
      val ipMaskOctets = CIDR.parseBlock(mask).head.split('.').toList

      val fullIp = ipMaskOctets.length match {
        case 1 => (ipMaskOctets ++ List("0", "0", "0")).mkString(".")
        case 2 => (ipMaskOctets ++ List("0", "0")).mkString(".")
        case 3 => (ipMaskOctets ++ List("0")).mkString(".")
        case 4 => ipMaskOctets.mkString(".")
      }

      val updatedMask = fullIp + "/" + CIDR.valueOf(mask).getMask

      CIDR.valueOf(updatedMask).contains(recordIpAddr)
    }.getOrElse(false)
  }

  private def ipv4ReverseSplitByOctets(string: String): List[String] =
    string.split('.').filter(!_.isEmpty).reverse.toList

  private def getZoneAsCIDRString(zone: Zone): Disjunction[Throwable, String] = {
    val zoneName = zone.name.split("in-addr.arpa.")(0)
    val zoneOctets = ipv4ReverseSplitByOctets(zoneName)
    val zoneString = zoneOctets.mkString(".")

    if (zoneString.contains("/")) {
      zoneString.right
    } else {
      zoneOctets.length match {
        case 1 => (zoneString + ".0.0.0/8").right
        case 2 => (zoneString + ".0.0/16").right
        case 3 => (zoneString + ".0/24").right
        case _ => InvalidRequest(s"Zone ${zone.name} does not have 1-3 octets: illegal").left
      }
    }
  }

  private def handleIpv4RecordValidation(
      zone: Zone,
      recordName: String): Disjunction[Throwable, Unit] = {
    val isValid = for {
      cidrMask <- getZoneAsCIDRString(zone)
      validated <- if (recordsetIsWithinCidrMask(cidrMask, zone, recordName)) {
        true.right
      } else {
        InvalidRequest(
          s"RecordSet $recordName does not specify a valid IP address in zone ${zone.name}").left
      }
    } yield validated

    isValid.map(_ => ())
  }

  private def handleIpv6RecordValidation(
      zone: Zone,
      recordName: String): Disjunction[Throwable, Unit] = {
    val v6Regex = "([0-9a-f][.]){32}ip6.arpa.".r

    s"$recordName.${zone.name}" match {
      case v6Regex(_*) => ().right
      case _ =>
        InvalidRequest(
          s"RecordSet $recordName does not specify a valid IP address in zone ${zone.name}").left
    }
  }

}
