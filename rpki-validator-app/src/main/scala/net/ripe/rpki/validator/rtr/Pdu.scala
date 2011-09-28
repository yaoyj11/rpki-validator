/**
 * The BSD License
 *
 * Copyright (c) 2010, 2011 RIPE NCC
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   - Redistributions of source code must retain the above copyright notice,
 *     this list of conditions and the following disclaimer.
 *   - Redistributions in binary form must reproduce the above copyright notice,
 *     this list of conditions and the following disclaimer in the documentation
 *     and/or other materials provided with the distribution.
 *   - Neither the name of the RIPE NCC nor the names of its contributors may be
 *     used to endorse or promote products derived from this software without
 *     specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.ripe.rpki.validator
package rtr

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import org.jboss.netty.buffer.ChannelBuffer
import org.jboss.netty.buffer.ChannelBuffers
import java.nio.ByteOrder
import scala.util.Random
import net.ripe.ipresource._
import java.math.BigInteger

sealed trait Pdu {
  def protocolVersion: Byte = 0
  def pduType: Byte
  def headerShort: Int = 0
  def length: Int
  
  assert(headerShort <= Pdu.MAX_HEADER_SHORT_VALUE)
}

object Pdu {
  val MAX_HEADER_SHORT_VALUE = 65536 - 1
}

case class BadData(errorCode: Int, content: Array[Byte])

/**
 * See: http://tools.ietf.org/html/draft-ietf-sidr-rpki-rtr-16#section-5.3
 */
case class ResetQueryPdu() extends Pdu {
  override def pduType = PduTypes.ResetQuery
  override def length = 8
}

/**
 * See: http://tools.ietf.org/html/draft-ietf-sidr-rpki-rtr-16#section-5.4
 */
case class CacheResponsePdu(nonce: Int = (new Random().nextInt() % Short.MaxValue)) extends Pdu {
  override def pduType = PduTypes.CacheResponse
  override def headerShort = nonce
  override def length = 8
}

/**
 * See: http://tools.ietf.org/html/draft-ietf-sidr-rpki-rtr-16#section-5.5
 */
case class IPv4PrefixAnnouncePdu(val ipv4PrefixStart: Ipv4Address, val prefixLength: Byte, val maxLength: Byte, val asn: Asn) extends Pdu {
  override def pduType = PduTypes.IPv4Prefix
  override def length = 20
}

/**
 * See: http://tools.ietf.org/html/draft-ietf-sidr-rpki-rtr-16#section-5.6
 */
case class IPv6PrefixAnnouncePdu(val ipv6PrefixStart: Ipv6Address, val prefixLength: Byte, val maxLength: Byte, val asn: Asn) extends Pdu {
  override def pduType = PduTypes.IPv6Prefix
  override def length = 32
}

case class EndOfDataPdu(nonce: Int, serial: Long) extends Pdu {
  override def pduType = PduTypes.EndOfData
  override def headerShort: Int = nonce
  override def length = 12

  assert(serial <= EndOfDataPdu.MAX_SERIAL)
}

object EndOfDataPdu {
  val MAX_SERIAL: Long = 4294967296L - 1
}

case class ErrorPdu(errorCode: Int, causingPdu: Array[Byte], errorText: String) extends Pdu {
  final override val pduType = PduTypes.Error
  override def headerShort = errorCode.toShort

  def causingPduLength = causingPdu.length

  val errorTextBytes: Array[Byte] = errorText.getBytes("UTF-8")
  val errorTextLength = errorTextBytes.length

  override val length = 8 + 4 + causingPduLength + 4 + errorTextLength
}

object ErrorPdu {
  val CorruptData = 0
  val InternalError = 1
  val NoDataAvailable = 2
  val InvalidRequest = 3
  val UnsupportedProtocolVersion = 4
  val UnsupportedPduType = 5
  val WithdrawalOfUnkownRecord = 6
  val DuplicateAnnouncementReceived = 7

  def isFatal(errorCode: Int) = errorCode != NoDataAvailable

}

object PduTypes {
  val ResetQuery: Byte = 2
  val CacheResponse: Byte = 3
  val IPv4Prefix: Byte = 4
  val IPv6Prefix: Byte = 6
  val EndOfData: Byte = 7
  val Error: Byte = 10
}

object Pdus {
  val SupportedProtocol: Byte = 0

  def encode(pdu: Pdu): Array[Byte] = {
    val buffer = ChannelBuffers.buffer(ByteOrder.BIG_ENDIAN, pdu.length)
    buffer.writeByte(pdu.protocolVersion)
    buffer.writeByte(pdu.pduType)
    buffer.writeShort(pdu.headerShort)
    buffer.writeInt(pdu.length)

    pdu match {
      case errorPdu @ ErrorPdu(errorCode, causingPdu, errorText) =>
        buffer.writeInt(causingPdu.length)
        buffer.writeBytes(causingPdu)
        buffer.writeInt(errorPdu.errorTextBytes.length)
        buffer.writeBytes(errorPdu.errorTextBytes)
      case ResetQueryPdu() =>
      case CacheResponsePdu(_) =>
      case IPv4PrefixAnnouncePdu(prefix, length, maxLength, asn) =>
        buffer.writeByte(1)
        buffer.writeByte(length)
        buffer.writeByte(maxLength)
        buffer.writeByte(0)
        buffer.writeBytes(convertToPrependedByteArray(prefix.getValue(), 4))
        buffer.writeBytes(convertToPrependedByteArray(asn.getValue(), 4))
      case IPv6PrefixAnnouncePdu(prefix, length, maxLength, asn) =>
        buffer.writeByte(1)
        buffer.writeByte(length)
        buffer.writeByte(maxLength)
        buffer.writeByte(0)
        buffer.writeBytes(convertToPrependedByteArray(prefix.getValue(), 16))
        buffer.writeBytes(convertToPrependedByteArray(asn.getValue(), 4))
      case EndOfDataPdu(_, serial) =>
        buffer.writeInt(serial.toInt)
    }

    buffer.array()
  }

  def convertToPrependedByteArray(value: BigInteger, bytesNeeded: Int): Array[Byte] = {
    var valueBytes = value.toByteArray()

    var extraBytesNeeded = bytesNeeded - valueBytes.length
    if (extraBytesNeeded > 0) {
      var prependBytes = new Array[Byte](extraBytesNeeded)
      prependBytes ++ valueBytes
    } else {
      valueBytes.drop(extraBytesNeeded * -1)
    }

  }

  def fromByteArray(buffer: ChannelBuffer): Either[BadData, Pdu] = try {
    val protocol = buffer.readByte()
    val pduType = buffer.readByte()
    val headerShort = buffer.readUnsignedShort()
    val length = buffer.readInt()

    if (protocol != SupportedProtocol) {
      Left(BadData(ErrorPdu.UnsupportedProtocolVersion, buffer.array))
    } else {
      pduType match {

        case PduTypes.Error =>
          val causingPduLength = buffer.readInt()
          val causingPdu = buffer.readBytes(causingPduLength).array()
          val errorTextLength = buffer.readInt()
          val errorTextBytes = buffer.readBytes(errorTextLength)
          val errorText = new String(buffer.array(), "UTF-8")
          Right(ErrorPdu(headerShort, causingPdu, errorText))

        case PduTypes.ResetQuery =>
          Right(ResetQueryPdu())

        case PduTypes.CacheResponse =>
          val nonce = headerShort
          Right(CacheResponsePdu(nonce))

        case PduTypes.EndOfData =>
          val nonce = headerShort
          val serial = buffer.readUnsignedInt()
          Right(EndOfDataPdu(nonce, serial))

        case PduTypes.IPv4Prefix =>
          buffer.readByte() match {
            case 1 =>
              val length = buffer.readByte()
              val maxLenght = buffer.readByte()
              buffer.skipBytes(1)
              val prefix = new Ipv4Address(buffer.readUnsignedInt())
              val asn = new Asn(buffer.readUnsignedInt())
              Right(IPv4PrefixAnnouncePdu(prefix, length, maxLenght, asn))
            case _ =>
              // TODO: Support withdrawals
              Left(BadData(ErrorPdu.UnsupportedPduType, buffer.array))
          }

        case PduTypes.IPv6Prefix =>
          buffer.readByte() match {
            case 1 =>
              val length = buffer.readByte()
              val maxLenght = buffer.readByte()
              buffer.skipBytes(1)
              val ipv6Bytes: Array[Byte] = new Array[Byte](16)
              buffer.getBytes(12, ipv6Bytes)
              val prefix = new Ipv6Address(new BigInteger(1, ipv6Bytes)) // Careful, omit sign and bad things happen when calling equals

              buffer.skipBytes(16)
              val asn = new Asn(buffer.readUnsignedInt())
              Right(IPv6PrefixAnnouncePdu(prefix, length, maxLenght, asn))
            case _ =>
              // TODO: Support withdrawals
              Left(BadData(ErrorPdu.UnsupportedPduType, buffer.array))
          }
        case _ =>
          Left(BadData(ErrorPdu.UnsupportedPduType, buffer.array))
      }
    }
  } catch {
    case e: IndexOutOfBoundsException =>
      Left(BadData(ErrorPdu.CorruptData, buffer.array()))
  }
}

