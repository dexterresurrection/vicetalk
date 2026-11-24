// A test program that plays «our program with an account» in the Windows check of shared sign-in (CI only):
//   <exe> <id> <origin or -> <title> <name> <allow|deny> <log file>
// One JSON line in, one JSON line out on the pipe uk.zjd.signin.<id>.<SID>, as docs of the protocol say. C# 5 (csc of Windows).
using System;
using System.IO;
using System.IO.Pipes;
using System.Net;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;

static class Donor {
	static void Main(string[] a) {
		string id = a[0], origin = a[1] == "-" ? "" : a[1], title = a[2], name = a[3], mode = a[4], log = a[5];
		string pipe = "uk.zjd.signin." + id + "." + WindowsIdentity.GetCurrent().User.Value;
		File.AppendAllText(log, "up\r\n");
		while (true) {
			using (var s = new NamedPipeServerStream(pipe, PipeDirection.InOut, 1, PipeTransmissionMode.Byte)) {
				s.WaitForConnection();
				try {
					var r = new StreamReader(s, new UTF8Encoding(false));
					string line = r.ReadLine();
					if (line == null) continue;
					File.AppendAllText(log, Regex.Replace(line, "[0-9A-F]{4}-[0-9A-F]{4}", "CODE") + "\r\n");
					string o = origin == "" ? "" : ",\"origin\":\"" + origin + "\"";
					string answer = "{\"ok\":true,\"signed_in\":true,\"name\":\"" + name + "\",\"title\":\"" + title + "\"" + o + "}";
					if (line.Contains("\"approve\"")) {
						if (mode == "allow") {
							var m = Regex.Match(line, "\"code\":\"([^\"]+)\"");
							using (var w = new WebClient()) {
								w.Headers["Authorization"] = "Bearer donor";
								w.UploadString("http://127.0.0.1:8081/api/cabinet/auth/device/approve", "{\"user_code\":\"" + m.Groups[1].Value + "\"}");
							}
							answer = "{\"ok\":true" + o + "}";
						} else {
							answer = "{\"ok\":false}";
						}
					}
					byte[] b = new UTF8Encoding(false).GetBytes(answer + "\n");
					s.Write(b, 0, b.Length);
					s.Flush();
					s.WaitForPipeDrain();
				} catch (Exception e) {
					File.AppendAllText(log, e.GetType().Name + "\r\n");
				}
			}
		}
	}
}
