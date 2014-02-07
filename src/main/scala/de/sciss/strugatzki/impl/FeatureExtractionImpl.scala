/*
 *  FeatureExtractionImpl.scala
 *  (Strugatzki)
 *
 *  Copyright (c) 2011-2014 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU General Public License v2+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.strugatzki
package impl

import de.sciss.synth
import synth.GE
import synth.io.AudioFile
import xml.XML
import concurrent.Await
import concurrent.duration.Duration
import de.sciss.processor.impl.ProcessorImpl

private[strugatzki] final class FeatureExtractionImpl(val config: FeatureExtraction.Config)
  extends FeatureExtraction with ProcessorImpl[Unit, FeatureExtraction] {

  import FeatureExtraction._

  protected def body(): Unit = {
    import NonRealtimeProcessor.{BufferSpec, RenderConfig, render}

    val inSpec      = AudioFile.readSpec(config.audioInput)
    val inChannels  = inSpec.numChannels
    val stepSize    = config.fftSize / config.fftOverlap
    val numFeatures = config.numCoeffs + 1
    val fftWinType  = 1 // -1 rect, 0 sine, 1 hann

    def extract(in0: GE): GE = {
      import synth._
      import ugen._

      val in      = config.channelsBehavior match {
        case ChannelsBehavior.Mix   => Mix(in0)
        case ChannelsBehavior.First => in0 \ 0
        case ChannelsBehavior.Last  => in0 \ (inChannels - 1)
      }
      val chain   = FFT("fft".kr, in, 1.0 / config.fftOverlap, fftWinType)
      val coeffs  = MFCC.kr(chain, config.numCoeffs)
      val loud    = Loudness.kr(chain) / 32
      val sig     = Flatten(Seq(loud, coeffs))
      sig
    }

    val fftBuf = BufferSpec("fft", numFrames = config.fftSize)

    val rCfg = RenderConfig(
      inFile        = config.audioInput,
      inSpec        = inSpec,
      numFeatures   = numFeatures,
      stepSize      = stepSize,
      buffers       = fftBuf :: Nil,
      outFile       = Some(config.featureOutput),
      progress      = progress(_),
      checkAborted  = () => checkAborted()
    )

    val r = render(rCfg)(extract)
    Await.result(r, Duration.Inf)

    config.metaOutput.foreach { metaFile =>
      val xml = config.toXML
      XML.save(metaFile.getAbsolutePath, xml, "UTF-8", xmlDecl = true, doctype = null)
    }
  }
}