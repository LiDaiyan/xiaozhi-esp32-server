import { getServiceUrl } from '../api';
import RequestService from '../httpRequest';

function request(path, method = 'GET', data = {}) {
  return new Promise((resolve, reject) => {
    RequestService.sendRequest()
      .url(`${getServiceUrl()}/project${path}`)
      .method(method)
      .data(data)
      .success(res => {
        RequestService.clearRequestTime();
        resolve(res.data.data);
      })
      .fail(error => {
        RequestService.clearRequestTime();
        reject(new Error(error.msg || '项目操作失败'));
      })
      .networkFail(() => {
        RequestService.clearRequestTime();
        reject(new Error('无法连接服务，请稍后重试'));
      }).send();
  });
}

export default {
  list: () => request(''),
  create: data => request('', 'POST', data),
  update: (id, data) => request(`/${encodeURIComponent(id)}`, 'PUT', data),
  devices: id => request(`/${encodeURIComponent(id)}/devices`),
  importDevices: (id, macAddresses) => request(`/${encodeURIComponent(id)}/devices`, 'POST', { macAddresses }),
  removeDevice: (id, mac) => request(`/${encodeURIComponent(id)}/devices/${encodeURIComponent(mac)}`, 'DELETE'),
};
