package weco.storage_service.generators

import weco.storage_service.bagit.models.{
  BagInfo,
  ExternalDescription,
  ExternalIdentifier,
  PayloadOxum
}

trait BagInfoGenerators
    extends ExternalIdentifierGenerators
    with PayloadOxumGenerators {

  def createBagInfoWith(
    payloadOxum: PayloadOxum = createPayloadOxum,
    externalIdentifier: ExternalIdentifier = createExternalIdentifier,
    externalDescription: Option[ExternalDescription] = Some(
      randomExternalDescription
    )
  ): BagInfo =
    BagInfo(
      externalIdentifier = externalIdentifier,
      payloadOxum = payloadOxum,
      baggingDate = randomLocalDate,
      sourceOrganisation = Some(randomSourceOrganisation),
      externalDescription = externalDescription,
      internalSenderIdentifier = Some(randomInternalSenderIdentifier),
      internalSenderDescription = Some(randomInternalSenderDescription)
    )

  def createBagInfo: BagInfo = createBagInfoWith()
}
