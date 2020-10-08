package uk.ac.wellcome.platform.archive.bag_tracker.client

import akka.http.scaladsl.model.Uri
import org.scalatest.concurrent.IntegrationPatience
import uk.ac.wellcome.fixtures.TestWith

class AkkaBagTrackerClientTest
    extends BagTrackerClientTestCases
    with IntegrationPatience {
  override def withClient[R](
    trackerHost: String
  )(testWith: TestWith[BagTrackerClient, R]): R =
    withActorSystem { implicit actorSystem =>
      val client = new AkkaBagTrackerClient(trackerHost = Uri(trackerHost))

      testWith(client)
    }
}
