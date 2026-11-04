-- mms-metro platform signage for CC:Tweaked monitors

-- Sign settings, used as-is when no station block is attached
local CONFIG = {
  station = "Central",
  line = "Line 1",
  lineColor = "red",
  direction = "Northbound",
  nextStation = "Harbour",
  transferLine = "",
  exitSide = "Left",
  terminus = false,
  textScale = 1,
  maxArrivals = 3,
  etaPadding = 1.5,
  refreshSeconds = 1,
}

-- Line colour names mapped to CC colours
local COLOR_NAMES = {
  white = colors.white,
  orange = colors.orange,
  magenta = colors.magenta,
  light_blue = colors.lightBlue,
  yellow = colors.yellow,
  lime = colors.lime,
  pink = colors.pink,
  gray = colors.gray,
  light_gray = colors.lightGray,
  cyan = colors.cyan,
  purple = colors.purple,
  blue = colors.blue,
  brown = colors.brown,
  green = colors.green,
  red = colors.red,
  black = colors.black,
}

-- Resolve a colour name, falling back to white
local function lineColour(name)
  return COLOR_NAMES[string.lower(name or "")] or colors.white
end

-- Pick black or white text for readability on a background
local function textOn(bg)
  if bg == colors.white or bg == colors.yellow or bg == colors.lime
    or bg == colors.lightBlue or bg == colors.lightGray or bg == colors.pink then
    return colors.black
  end
  return colors.white
end

-- Write text centred on a row
local function centre(mon, y, text, fg, bg)
  local w = mon.getSize()
  mon.setCursorPos(1, y)
  mon.setBackgroundColor(bg)
  mon.write(string.rep(" ", w))
  local x = math.max(1, math.floor((w - #text) / 2) + 1)
  mon.setCursorPos(x, y)
  mon.setTextColor(fg)
  mon.write(text:sub(1, w))
end

-- Write a label and value on one row
local function row(mon, y, label, value, accent)
  local w = mon.getSize()
  mon.setBackgroundColor(colors.black)
  mon.setCursorPos(1, y)
  mon.write(string.rep(" ", w))
  mon.setCursorPos(2, y)
  mon.setTextColor(colors.lightGray)
  mon.write(label)
  mon.setTextColor(accent or colors.white)
  mon.write((" " .. value):sub(1, math.max(0, w - #label - 1)))
end

-- Format seconds as m:ss
local function clock(seconds)
  return string.format("%d:%02d", math.floor(seconds / 60), seconds % 60)
end

-- Upper-bound whole minutes for an ETA, padded since the raw ETA assumes top speed
local function upperMinutes(seconds)
  return math.max(1, math.ceil(seconds * CONFIG.etaPadding / 60))
end

-- Live station settings and arrivals, or CONFIG and no arrivals without a station
local function readStation(station)
  if not station then
    return CONFIG, nil
  end
  local ok, info = pcall(station.getStation)
  local okArr, arrivals = pcall(station.getArrivals)
  if not ok then
    return CONFIG, nil
  end
  return {
    station = info.name,
    line = info.line,
    lineColor = info.lineColor,
    direction = info.direction,
    nextStation = info.nextStation,
    transferLine = info.hub and info.transferLine or "",
    exitSide = info.exitSide,
    terminus = info.terminus,
  }, okArr and arrivals or {}
end

-- Draw the arrivals board from the given row down
local function drawArrivals(mon, y, arrivals, blink)
  local _, h = mon.getSize()
  if #arrivals == 0 then
    row(mon, y, "No trains due", "", colors.gray)
    return
  end
  for i = 1, math.min(#arrivals, CONFIG.maxArrivals) do
    if y >= h then
      return
    end
    local a = arrivals[i]
    local label = (a.line ~= "" and a.line or "Train") .. " " .. a.direction
    local status
    local accent = colors.white
    if a.atPlatform then
      status = "dep " .. clock(a.departsIn)
      accent = blink and colors.lime or colors.green
    else
      status = upperMinutes(a.eta) .. " min"
    end
    row(mon, y, label, status, accent)
    y = y + 1
  end
end

-- Draw the full sign on one monitor
local function draw(mon, info, arrivals, blink)
  mon.setTextScale(CONFIG.textScale)
  mon.setBackgroundColor(colors.black)
  mon.clear()
  local w, h = mon.getSize()
  local bar = lineColour(info.lineColor)
  centre(mon, 1, info.line, textOn(bar), bar)
  centre(mon, 2, info.station, colors.white, colors.black)
  local y = 4
  if info.terminus then
    row(mon, y, "This train", "terminates here", colors.orange)
  else
    if info.direction ~= "" then
      row(mon, y, "Towards", info.direction, bar == colors.black and colors.white or bar)
      y = y + 1
    end
    row(mon, y, "Next stop", info.nextStation)
  end
  y = y + 1
  if info.transferLine ~= "" then
    row(mon, y, "Change for", info.transferLine, colors.yellow)
    y = y + 1
  end
  if info.exitSide ~= "" then
    row(mon, y, "Doors open", info.exitSide)
    y = y + 1
  end
  if arrivals then
    drawArrivals(mon, y + 1, arrivals, blink)
  end
  if h >= 3 then
    centre(mon, h, textutils.formatTime(os.time(), true), colors.white, colors.gray)
  end
  mon.setCursorPos(w, h)
end

-- Every attached monitor, so one computer can drive several
local function monitors()
  local found = { peripheral.find("monitor") }
  if #found == 0 then
    error("No monitor attached", 0)
  end
  return found
end

-- Main loop, redraws on timer or peripheral changes
local function main()
  local mons = monitors()
  local station = peripheral.find("metro_station")
  local blink = false
  local timer = os.startTimer(CONFIG.refreshSeconds)
  while true do
    local info, arrivals = readStation(station)
    for _, mon in ipairs(mons) do
      draw(mon, info, arrivals, blink)
    end
    local event, id = os.pullEvent()
    if event == "timer" and id == timer then
      blink = not blink
      timer = os.startTimer(CONFIG.refreshSeconds)
    elseif event == "peripheral" or event == "peripheral_detach" or event == "monitor_resize" then
      mons = monitors()
      station = peripheral.find("metro_station")
    end
  end
end

main()
