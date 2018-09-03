package com.wavesplatform.matcher.util

import java.nio.ByteBuffer

import com.wavesplatform.state.ByteStr
import scorex.transaction.AssetId
import scorex.transaction.assets.exchange.AssetPair

object Codecs {
  def len(assetId: Option[AssetId]): Int = assetId.fold(1)(1 + _.arr.length)

  def len(x: AssetPair): Int = len(x.amountAsset) + len(x.priceAsset)

  implicit class ByteBufferExt(val b: ByteBuffer) extends AnyVal {
    def putAssetId(assetId: Option[AssetId]): ByteBuffer = assetId match {
      case None => b.put(0.toByte)
      case Some(aid) =>
        require(aid.arr.length < Byte.MaxValue, "Asset ID is too long")
        b.put(aid.arr.length.toByte).put(aid.arr)
    }

    def getAssetId: Option[AssetId] = b.get() match {
      case 0 => None
      case len =>
        val arr = new Array[Byte](len)
        b.get(arr)
        Some(ByteStr(arr))
    }

    def putAssetPair(x: AssetPair): ByteBuffer = {
      putAssetId(x.amountAsset)
      putAssetId(x.priceAsset)
    }

    def getAssetPair: AssetPair = AssetPair(getAssetId, getAssetId)
  }
}
