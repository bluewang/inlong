/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "recv_group.h"

#include "../protocol/msg_protocol.h"
#include "../utils/capi_constant.h"
#include "../utils/utils.h"
#include "api_code.h"
#include <cstdlib>
#include <functional>

namespace inlong {
const uint32_t DEFAULT_PACK_ATTR = 400;
RecvGroup::RecvGroup(const std::string &group_key,std::shared_ptr<SendManager> send_manager)
    : cur_len_(0), groupId_num_(0), streamId_num_(0),
      msg_type_(SdkConfig::getInstance()->msg_type_),
      data_capacity_(SdkConfig::getInstance()->buf_size_),
      send_manager_(send_manager),group_key_(group_key) {
  data_capacity_ = std::max(SdkConfig::getInstance()->max_msg_size_,
                            SdkConfig::getInstance()->pack_size_);
  data_capacity_ = data_capacity_ + DEFAULT_PACK_ATTR;

  pack_buf_ = new char[data_capacity_];
  memset(pack_buf_, 0x0, data_capacity_);
  data_time_ = 0;
  last_pack_time_ = Utils::getCurrentMsTime();
  max_recv_size_ = SdkConfig::getInstance()->recv_buf_size_;

  LOG_INFO("RecvGroup:"<<group_key_<<",data_capacity:"<<data_capacity_<<",max_recv_size:"<<max_recv_size_);
}

RecvGroup::~RecvGroup() {
  if (pack_buf_) {
    delete[] pack_buf_;
    pack_buf_ = nullptr;
  }
}

int32_t RecvGroup::SendData(const std::string &msg, const std::string &groupId,
                            const std::string &streamId,
                            const std::string &client_ip, uint64_t report_time,
                            UserCallBack call_back) {
  std::lock_guard<std::mutex> lck(mutex_);

  if (msg.size() + cur_len_ > max_recv_size_) {
    return SdkCode::kRecvBufferFull;
  }

  AddMsg(msg, client_ip, report_time, call_back,groupId,streamId);

  return SdkCode::kSuccess;
}

int32_t RecvGroup::DoDispatchMsg() {
  last_pack_time_ = Utils::getCurrentMsTime();
  std::lock_guard<std::mutex> lck(mutex_);
  if (group_key_.empty()) {
    LOG_ERROR("groupId  is empty, check!!");
    return SdkCode::kInvalidInput;
  }
  if (msgs_.empty()) {
    LOG_ERROR("no msg in msg_set, check!");
    return SdkCode::kMsgEmpty;
  }
  auto send_group = send_manager_->GetSendGroup(group_key_);
  if (send_group == nullptr) {
    LOG_ERROR("failed to get send_buf, something gets wrong, checkout!");
    return SdkCode::kFailGetSendBuf;
  }
  if (!send_group->IsAvailable()) {
    return SdkCode::kFailGetConn;
  }
  if (send_group->IsFull()) {
    return SdkCode::kSendBufferFull;
  }

  uint32_t total_length = 0;
  uint64_t max_tid_size = 0;
  std::unordered_map<std::string, std::vector<SdkMsgPtr>> msgs_to_dispatch;
  std::unordered_map<std::string, uint64_t> tid_stat;
  while (!msgs_.empty()) {
    SdkMsgPtr msg = msgs_.front();
    if (msg->msg_.size() + max_tid_size + constants::ATTR_LENGTH > SdkConfig::getInstance()->pack_size_) {
      if (!msgs_to_dispatch.empty()) {
        break;
      }
    }
    std::string msg_key = msg->inlong_group_id_ + msg->inlong_stream_id_;
    msgs_to_dispatch[msg_key].push_back(msg);
    msgs_.pop();

    total_length = msg->msg_.size() + total_length + constants::ATTR_LENGTH;

    if (tid_stat.find(msg_key) == tid_stat.end()) {
      tid_stat[msg_key] = 0;
    }
    tid_stat[msg_key] = tid_stat[msg_key] + msg->msg_.size() + constants::ATTR_LENGTH;

    max_tid_size = std::max(tid_stat[msg_key], max_tid_size);
  }

  cur_len_ = cur_len_ - total_length;

  for (auto it : msgs_to_dispatch) {
    std::shared_ptr<SendBuffer> send_buffer = BuildSendBuf(it.second);

    ResetPackBuf();

    if (send_buffer == nullptr) {
      CallbalkToUsr(it.second);
      continue;
    }

    int ret = send_group->PushData(send_buffer);
    if (ret != SdkCode::kSuccess) {
      CallbalkToUsr(it.second);
    }
  }

  return SdkCode::kSuccess;
}

void RecvGroup::AddMsg(const std::string &msg, std::string client_ip,
                       int64_t report_time, UserCallBack call_back,const std::string &groupId,
                       const std::string &streamId) {
  if (Utils::isLegalTime(report_time))
    data_time_ = report_time;
  else {
    data_time_ = Utils::getCurrentMsTime();
  }

  std::string user_client_ip = client_ip;
  int64_t user_report_time = report_time;

  if (client_ip.empty()) {
    client_ip = "127.0.0.1";
  }
  std::string data_pack_format_attr =
      "__addcol1__reptime=" + Utils::getFormatTime(data_time_) +
      "&__addcol2__ip=" + client_ip;
  msgs_.push(std::make_shared<SdkMsg>(msg, client_ip, data_time_, call_back,
                                      data_pack_format_attr, user_client_ip,
                                      user_report_time,groupId,streamId));

  cur_len_ += msg.size() + constants::ATTR_LENGTH;
}

bool RecvGroup::PackMsg(std::vector<SdkMsgPtr> &msgs, char *pack_data,
                        uint32_t &out_len, uint32_t uniq_id) {
  if (pack_data == nullptr) {
    LOG_ERROR("nullptr, failed to allocate memory for buf");
    return false;
  }
  uint32_t idx = 0;
  for (auto &it : msgs) {
    if (msg_type_ >= 5) {
      *(uint32_t *)(&pack_buf_[idx]) = htonl(it->msg_.size());
      idx += sizeof(uint32_t);
    }
    memcpy(&pack_buf_[idx], it->msg_.data(), it->msg_.size());
    idx += static_cast<uint32_t>(it->msg_.size());

    // add attrlen|attr
    if (SdkConfig::getInstance()->isAttrDataPackFormat()) {
      *(uint32_t *)(&pack_buf_[idx]) = htonl(it->data_pack_format_attr_.size());
      idx += sizeof(uint32_t);

      memcpy(&pack_buf_[idx], it->data_pack_format_attr_.data(),
             it->data_pack_format_attr_.size());
      idx += static_cast<uint32_t>(it->data_pack_format_attr_.size());
    }

    if (msg_type_ == 2 || msg_type_ == 3) {
      pack_buf_[idx] = '\n';
      ++idx;
    }
  }

  uint32_t cnt = 1;
  if (msgs.size()) {
    cnt = msgs.size();
  }

  if (msg_type_ >= constants::kBinPackMethod) {
    char *bodyBegin = pack_data + sizeof(BinaryMsgHead) + sizeof(uint32_t);
    uint32_t body_len = 0;

    std::string snappy_res;
    bool isSnappy = IsZipAndOperate(snappy_res, idx);
    char real_msg_type;

    if (isSnappy) {
      body_len = static_cast<uint32_t>(snappy_res.size());
      memcpy(bodyBegin, snappy_res.data(), body_len);
      // msg_type
      real_msg_type = (msg_type_ | constants::kBinSnappyFlag);
    } else {
      body_len = idx;
      memcpy(bodyBegin, pack_buf_, body_len);
      real_msg_type = msg_type_;
    }
    *(uint32_t *)(&(pack_data[sizeof(BinaryMsgHead)])) = htonl(body_len);

    bodyBegin += body_len;

    uint32_t char_groupId_flag = 0;
    std::string groupId_streamId_char;
    uint16_t groupId_num = 0, streamId_num = 0;
    if (SdkConfig::getInstance()->enableChar() || groupId_num_ == 0 ||
        streamId_num_ == 0) {
      groupId_num = 0;
      streamId_num = 0;
      groupId_streamId_char = "groupId=" + msgs[0]->inlong_group_id_ +
                              "&streamId=" + msgs[0]->inlong_stream_id_;
      char_groupId_flag = 0x4;
    } else {
      groupId_num = groupId_num_;
      streamId_num = streamId_num_;
    }
    uint16_t ext_field =
        (SdkConfig::getInstance()->extend_field_ | char_groupId_flag);
    uint32_t data_time = data_time_ / 1000;

    std::string attr;
    if (SdkConfig::getInstance()->enableTraceIP()) {
      if (groupId_streamId_char.empty())
        attr = "node1ip=" + SdkConfig::getInstance()->local_ip_ +
               "&rtime1=" + std::to_string(Utils::getCurrentMsTime());
      else
        attr = groupId_streamId_char +
               "&node1ip=" + SdkConfig::getInstance()->local_ip_ +
               "&rtime1=" + std::to_string(Utils::getCurrentMsTime());
    } else {
      attr = "groupId=" + msgs[0]->inlong_group_id_ +
             "&streamId=" + msgs[0]->inlong_stream_id_;
    }
    *(uint16_t *)bodyBegin = htons(attr.size());
    bodyBegin += sizeof(uint16_t);
    memcpy(bodyBegin, attr.data(), attr.size());
    bodyBegin += attr.size();

    *(uint16_t *)bodyBegin = htons(constants::kBinaryMagic);

    uint32_t total_len = 25 + body_len + attr.size();

    char *p = pack_data;
    *(uint32_t *)p = htonl(total_len);
    p += 4;
    *p = real_msg_type;
    ++p;
    *(uint16_t *)p = htons(groupId_num);
    p += 2;
    *(uint16_t *)p = htons(streamId_num);
    p += 2;
    *(uint16_t *)p = htons(ext_field);
    p += 2;
    *(uint32_t *)p = htonl(data_time);
    p += 4;
    *(uint16_t *)p = htons(cnt);
    p += 2;
    *(uint32_t *)p = htonl(uniq_id);

    out_len = total_len + 4;
  } else {
    if (msg_type_ == 3 || msg_type_ == 2) {
      --idx;
    }

    char *bodyBegin = pack_data + sizeof(ProtocolMsgHead) + sizeof(uint32_t);
    uint32_t body_len = 0;
    std::string snappy_res;
    bool isSnappy = IsZipAndOperate(snappy_res, idx);
    if (isSnappy) {
      body_len = static_cast<uint32_t>(snappy_res.size());
      memcpy(bodyBegin, snappy_res.data(), body_len);
    } else {
      body_len = idx;
      memcpy(bodyBegin, pack_buf_, body_len);
    }
    *(uint32_t *)(&(pack_data[sizeof(ProtocolMsgHead)])) = htonl(body_len);
    bodyBegin += body_len;

    // attr
    std::string attr;
    attr = "groupId=" + msgs[0]->inlong_group_id_ +
           "&streamId=" + msgs[0]->inlong_stream_id_;

    attr += "&dt=" + std::to_string(data_time_);
    attr += "&mid=" + std::to_string(uniq_id);
    if (isSnappy)
      attr += "&cp=snappy";
    attr += "&cnt=" + std::to_string(cnt);
    attr += "&sid=" + std::string(Utils::getSnowflakeId());

    *(uint32_t *)bodyBegin = htonl(attr.size());
    bodyBegin += sizeof(uint32_t);
    memcpy(bodyBegin, attr.data(), attr.size());

    // total_len
    uint32_t total_len = 1 + 4 + body_len + 4 + attr.size();
    *(uint32_t *)pack_data = htonl(total_len);
    // msg_type
    *(&pack_data[4]) = msg_type_;
    out_len = total_len + 4;
  }
  return true;
}

bool RecvGroup::IsZipAndOperate(std::string &res, uint32_t real_cur_len) {
  if (SdkConfig::getInstance()->enable_zip_ &&
      real_cur_len > SdkConfig::getInstance()->min_zip_len_) {
    Utils::zipData(pack_buf_, real_cur_len, res);
    return true;
  } else
    return false;
}

void RecvGroup::DispatchMsg(bool exit) {
  if (cur_len_ <= constants::ATTR_LENGTH || msgs_.empty())
    return;
  bool len_enough = cur_len_ > SdkConfig::getInstance()->pack_size_;
  bool time_enough = (Utils::getCurrentMsTime() - last_pack_time_) >
                     SdkConfig::getInstance()->pack_timeout_;
  if (len_enough || time_enough) {
    DoDispatchMsg();
  }
}
std::shared_ptr<SendBuffer>
RecvGroup::BuildSendBuf(std::vector<SdkMsgPtr> &msgs) {
  if (msgs.empty()) {
    LOG_ERROR("pack msgs is empty.");
    return nullptr;
  }
  std::shared_ptr<SendBuffer> send_buffer =
      std::make_shared<SendBuffer>(data_capacity_);
  if (send_buffer == nullptr) {
    LOG_ERROR("make send buffer failed.");
    return nullptr;
  }
  uint32_t len = 0;
  int32_t msg_cnt = msgs.size();
  uint32_t uniq_id = g_send_msgid.incrementAndGet();

  if (!PackMsg(msgs, send_buffer->content(), len, uniq_id) || len == 0) {
    LOG_ERROR("failed to write data to send buf from pack queue, sendQueue "
              "id:%d, buf id:%d");
    return nullptr;
  }
  send_buffer->setLen(len);
  send_buffer->setMsgCnt(msg_cnt);
  send_buffer->setInlongGroupId(msgs[0]->inlong_group_id_);
  send_buffer->setStreamId(msgs[0]->inlong_stream_id_);
  send_buffer->setUniqId(uniq_id);
  send_buffer->setIsPacked(true);
  for (auto it : msgs) {
    send_buffer->addUserMsg(it);
  }

  return send_buffer;
}

void RecvGroup::CallbalkToUsr(std::vector<SdkMsgPtr> &msgs) {
  for (auto &it : msgs) {
    if (it->cb_) {
      it->cb_(it->inlong_group_id_.data(), it->inlong_stream_id_.data(),
              it->msg_.data(), it->msg_.size(), it->user_report_time_,
              it->user_client_ip_.data());
    }
  }
}
} // namespace inlong