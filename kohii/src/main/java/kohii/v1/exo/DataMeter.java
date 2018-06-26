/*
 * Copyright (c) 2018 Nam Nguyen, nam@ene.im
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package kohii.v1.exo;

import android.support.annotation.NonNull;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

/**
 * @author eneim (2018/06/25).
 */
@SuppressWarnings("WeakerAccess") //
public class DataMeter<T extends BandwidthMeter, S extends TransferListener<Object>>
    implements BandwidthMeter, TransferListener<Object> {

  @NonNull protected final T bandwidthMeter;
  @NonNull protected final S transferListener;

  public DataMeter(@NonNull T bandwidthMeter, @NonNull S transferListener) {
    this.bandwidthMeter = bandwidthMeter;
    this.transferListener = transferListener;
  }

  @Override public long getBitrateEstimate() {
    return bandwidthMeter.getBitrateEstimate();
  }

  @Override public void onTransferStart(Object source, DataSpec dataSpec) {
    transferListener.onTransferStart(source, dataSpec);
  }

  @Override public void onBytesTransferred(Object source, int bytesTransferred) {
    transferListener.onBytesTransferred(source, bytesTransferred);
  }

  @Override public void onTransferEnd(Object source) {
    transferListener.onTransferEnd(source);
  }
}
