/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.net.InetAddress

import akka.io.dns.DnsProtocol.{ Ip, RequestType, Srv }
import akka.io.{ Dns, IO }
import akka.pattern.ask
import akka.testkit.{ AkkaSpec, SocketUtil }
import akka.util.Timeout

import scala.concurrent.duration._

/*
These tests rely on a DNS server with 2 zones configured, foo.test and bar.example.

The configuration to start a bind DNS server in Docker with this configuration
is included, and the test will automatically start this container when the
test starts and tear it down when it finishes.
*/
class AsyncDnsResolverIntegrationSpec extends AkkaSpec(
  s"""
    akka.loglevel = DEBUG
    akka.io.dns.resolver = async-dns
    akka.io.dns.async-dns.nameservers = ["localhost:${AsyncDnsResolverIntegrationSpec.dockerDnsServerPort}"]
//    akka.io.dns.async-dns.nameservers = default
  """) with DockerBindDnsService {
  val duration = 10.seconds
  implicit val timeout = Timeout(duration)

  val hostPort = AsyncDnsResolverIntegrationSpec.dockerDnsServerPort

  "Resolver" must {
    if (!dockerAvailable())
      pending

    "resolve single A record" in {
      val name = "a-single.foo.test"
      val answer = resolve(name, DnsProtocol.Ip(ipv6 = false))
      withClue(answer) {
        answer.name shouldEqual name
        answer.records.size shouldEqual 1
        answer.records.head.name shouldEqual name
        answer.records.head.asInstanceOf[ARecord].ip shouldEqual InetAddress.getByName("192.168.1.20")
      }
    }

    "resolve double A records" in {
      val name = "a-double.foo.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.records.map(_.asInstanceOf[ARecord].ip).toSet shouldEqual Set(
        InetAddress.getByName("192.168.1.21"),
        InetAddress.getByName("192.168.1.22")
      )
    }

    "resolve single AAAA record" in {
      val name = "aaaa-single.foo.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.records.map(_.asInstanceOf[AAAARecord].ip) shouldEqual Seq(InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:1"))
    }

    "resolve double AAAA records" in {
      val name = "aaaa-double.foo.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.records.map(_.asInstanceOf[AAAARecord].ip).toSet shouldEqual Set(
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:2"),
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:3")
      )
    }

    "resolve mixed A/AAAA records" in {
      val name = "a-aaaa.foo.test"
      val answer = resolve(name)
      answer.name shouldEqual name

      answer.records.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("192.168.1.23"),
        InetAddress.getByName("192.168.1.24")
      )

      answer.records.collect { case r: AAAARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:4"),
        InetAddress.getByName("fd4d:36b2:3eca:a2d8:0:0:0:5")
      )
    }

    "resolve external CNAME record" in {
      val name = "cname-ext.foo.test"
      val answer = (IO(Dns) ? DnsProtocol.Resolve(name)).mapTo[DnsProtocol.Resolved].futureValue
      answer.name shouldEqual name
      answer.records.collect { case r: CNameRecord ⇒ r.canonicalName }.toSet shouldEqual Set(
        "a-single.bar.example"
      )
      answer.records.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("192.168.2.20")
      )
    }

    "resolve internal CNAME record" in {
      val name = "cname-in.foo.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.records.collect { case r: CNameRecord ⇒ r.canonicalName }.toSet shouldEqual Set(
        "a-double.foo.test"
      )
      answer.records.collect { case r: ARecord ⇒ r.ip }.toSet shouldEqual Set(
        InetAddress.getByName("192.168.1.21"),
        InetAddress.getByName("192.168.1.22")
      )
    }

    "resolve SRV record" in {
      val name = "service.tcp.foo.test"
      val answer = resolve("service.tcp.foo.test", Srv)

      answer.name shouldEqual name
      answer.records.collect { case r: SRVRecord ⇒ r }.toSet shouldEqual Set(
        SRVRecord("service.tcp.foo.test", 86400, 10, 60, 5060, "a-single.foo.test"),
        SRVRecord("service.tcp.foo.test", 86400, 10, 40, 5070, "a-double.foo.test")
      )
    }

    "resolve same address twice" in {
      resolve("a-single.foo.test").records.map(_.asInstanceOf[ARecord].ip) shouldEqual Seq(InetAddress.getByName("192.168.1.20"))
      resolve("a-single.foo.test").records.map(_.asInstanceOf[ARecord].ip) shouldEqual Seq(InetAddress.getByName("192.168.1.20"))
    }

    "handle nonexistent domains" in {
      val answer = (IO(Dns) ? DnsProtocol.Resolve("nonexistent.foo.test")).mapTo[DnsProtocol.Resolved].futureValue
      answer.records shouldEqual List.empty
    }

    "resolve queries that are too big for UDP" in {
      val name = "many.foo.test"
      val answer = resolve(name)
      answer.name shouldEqual name
      answer.records.length should be(48)
    }

    def resolve(name: String, requestType: RequestType = Ip()): DnsProtocol.Resolved = {
      (IO(Dns) ? DnsProtocol.Resolve(name, requestType)).mapTo[DnsProtocol.Resolved].futureValue
    }

  }
}
object AsyncDnsResolverIntegrationSpec {
  lazy val dockerDnsServerPort = SocketUtil.temporaryLocalPort()
}
